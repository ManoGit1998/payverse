package com.payverse.common.web.idempotency;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Executes a money-moving request at most once per {@code (scope, Idempotency-Key)}.
 * <ul>
 *   <li>Redis {@code SET NX} claims the key, so concurrent duplicates get {@code 409 REQUEST_IN_PROGRESS}.</li>
 *   <li>The action and the durable {@code idempotency_records} row commit in <b>one</b> DB transaction
 *       (the action's {@code @Transactional} joins it), so a crash can never leave a committed effect
 *       without its replayable response.</li>
 *   <li>Completed responses are cached in Redis and replayed with header {@value #REPLAYED_HEADER}.</li>
 *   <li>Same key with a different body → {@code 422 IDEMPOTENCY_KEY_REUSED}.</li>
 * </ul>
 * 5xx responses and exceptions release the claim so the client can retry.
 */
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final IdempotencyProperties properties;

    public IdempotencyService(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                              JsonMapper jsonMapper, IdempotencyProperties properties) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    record Entry(State state, String requestHash, int status, String body) {
        enum State { IN_PROGRESS, COMPLETED }
    }

    /**
     * @param scope        caller scope, e.g. {@code "transfer:" + userId}
     * @param key          the client's Idempotency-Key header
     * @param request      request payload (hashed to detect key reuse)
     * @param responseType response body type, for replay
     * @param action       the operation; its transaction joins the idempotency transaction
     */
    public <T> ResponseEntity<T> execute(String scope, String key, Object request, Class<T> responseType,
                                         Supplier<ResponseEntity<T>> action) {
        if (key == null || key.isBlank()) {
            throw new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_MISSING, null);
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new PayverseException(CommonErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8-64 characters of [A-Za-z0-9_-]");
        }
        String hash = hash(request);
        String redisKey = "idem:" + scope + ":" + key;

        Optional<Entry> existing = findCompleted(redisKey, scope, key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, responseType);
        }

        Entry claim = new Entry(Entry.State.IN_PROGRESS, hash, 0, null);
        Boolean claimed = redis.opsForValue().setIfAbsent(redisKey, write(claim), properties.lockTtl());
        if (!Boolean.TRUE.equals(claimed)) {
            Entry current = read(redis.opsForValue().get(redisKey));
            if (current != null && current.state() == Entry.State.COMPLETED) {
                return replay(current, hash, responseType);
            }
            if (current != null && !current.requestHash().equals(hash)) {
                throw new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null);
            }
            throw new PayverseException(CommonErrorCode.REQUEST_IN_PROGRESS, null);
        }

        try {
            ResponseEntity<T> response = transactionTemplate.execute(status -> {
                ResponseEntity<T> result = action.get();
                if (!result.getStatusCode().is5xxServerError()) {
                    saveDurable(scope, key, hash, result);
                }
                return result;
            });
            if (response == null || response.getStatusCode().is5xxServerError()) {
                redis.delete(redisKey);
                return response;
            }
            Entry completed = new Entry(Entry.State.COMPLETED, hash, response.getStatusCode().value(), write(response.getBody()));
            redis.opsForValue().set(redisKey, write(completed), properties.ttl());
            return response;
        } catch (RuntimeException e) {
            redis.delete(redisKey);
            throw e;
        }
    }

    private Optional<Entry> findCompleted(String redisKey, String scope, String key) {
        Entry cached = read(redis.opsForValue().get(redisKey));
        if (cached != null && cached.state() == Entry.State.COMPLETED) {
            return Optional.of(cached);
        }
        if (cached != null) {
            return Optional.empty(); // in progress: handled by the claim step
        }
        List<Entry> rows = jdbc.query("""
                        SELECT request_hash, response_status, response_body::text AS body
                        FROM idempotency_records
                        WHERE scope = ? AND idempotency_key = ? AND expires_at > now()
                        """,
                (rs, i) -> new Entry(Entry.State.COMPLETED, rs.getString("request_hash"), rs.getInt("response_status"),
                        rs.getString("body")),
                scope, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = rows.getFirst();
        redis.opsForValue().set(redisKey, write(entry), properties.ttl());
        return Optional.of(entry);
    }

    private <T> ResponseEntity<T> replay(Entry entry, String hash, Class<T> responseType) {
        if (!entry.requestHash().equals(hash)) {
            throw new PayverseException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, null);
        }
        log.info("Replaying idempotent response (status {})", entry.status());
        T body = entry.body() == null || "null".equals(entry.body()) ? null : jsonMapper.readValue(entry.body(), responseType);
        return ResponseEntity.status(HttpStatusCode.valueOf(entry.status())).header(REPLAYED_HEADER, "true").body(body);
    }

    private void saveDurable(String scope, String key, String hash, ResponseEntity<?> response) {
        Instant expiresAt = Instant.now().plus(properties.ttl());
        jdbc.update("""
                        INSERT INTO idempotency_records (scope, idempotency_key, request_hash, response_status, response_body, expires_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (scope, idempotency_key) DO UPDATE
                           SET request_hash = EXCLUDED.request_hash, response_status = EXCLUDED.response_status,
                               response_body = EXCLUDED.response_body, created_at = now(), expires_at = EXCLUDED.expires_at
                         WHERE idempotency_records.expires_at <= now()
                        """,
                scope, key, hash, response.getStatusCode().value(), write(response.getBody()), Timestamp.from(expiresAt));
    }

    /** Deletes expired durable records; call from a scheduled job. */
    public int purgeExpired() {
        return jdbc.update("DELETE FROM idempotency_records WHERE expires_at <= now()");
    }

    private String hash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    private Entry read(String json) {
        return json == null ? null : jsonMapper.readValue(json, Entry.class);
    }
}
