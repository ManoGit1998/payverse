package com.payverse.common.web.idempotency;

import com.payverse.common.error.CommonErrorCode;
import com.payverse.common.error.PayverseException;
import com.payverse.common.test.PostgresTestcontainer;
import com.payverse.common.test.RedisTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = IdempotencyTestApplication.class, properties = {
        "spring.application.name=common-web",
        "spring.main.web-application-type=none",
        "spring.flyway.locations=classpath:db/common"
})
@Import({PostgresTestcontainer.class, RedisTestcontainer.class})
class IdempotencyServiceIT {

    record TransferRequest(String to, String amount) {
    }

    record TransferResponse(String transferId, String status) {
    }

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    private final TransferRequest request = new TransferRequest("9876543210", "100.00");

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String scope() {
        return "transfer:" + UUID.randomUUID();
    }

    @Test
    void executesOnceAndReplaysTheSameResponse() {
        String scope = scope();
        String key = newKey();
        AtomicInteger executions = new AtomicInteger();

        ResponseEntity<TransferResponse> first = idempotency.execute(scope, key, request, TransferResponse.class, () -> {
            executions.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(new TransferResponse("t-1", "SUCCESS"));
        });
        ResponseEntity<TransferResponse> second = idempotency.execute(scope, key, request, TransferResponse.class, () -> {
            executions.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(new TransferResponse("t-2", "SUCCESS"));
        });

        assertThat(executions).hasValue(1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(first.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER)).isNull();
    }

    @Test
    void replaysFromDatabaseWhenRedisIsEmpty() {
        String scope = scope();
        String key = newKey();
        idempotency.execute(scope, key, request, TransferResponse.class,
                () -> ResponseEntity.ok(new TransferResponse("t-db", "SUCCESS")));

        redis.delete("idem:" + scope + ":" + key);

        ResponseEntity<TransferResponse> replay = idempotency.execute(scope, key, request, TransferResponse.class,
                () -> { throw new AssertionError("must not execute again"); });
        assertThat(replay.getBody()).isEqualTo(new TransferResponse("t-db", "SUCCESS"));
    }

    @Test
    void rejectsKeyReuseWithDifferentBody() {
        String scope = scope();
        String key = newKey();
        idempotency.execute(scope, key, request, TransferResponse.class,
                () -> ResponseEntity.ok(new TransferResponse("t", "SUCCESS")));

        assertThatThrownBy(() -> idempotency.execute(scope, key, new TransferRequest("9876543210", "999.00"),
                TransferResponse.class, () -> ResponseEntity.ok(new TransferResponse("x", "SUCCESS"))))
                .isInstanceOfSatisfying(PayverseException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void releasesClaimWhenActionFails() {
        String scope = scope();
        String key = newKey();
        assertThatThrownBy(() -> idempotency.execute(scope, key, request, TransferResponse.class,
                () -> { throw new IllegalStateException("downstream timeout"); }))
                .isInstanceOf(IllegalStateException.class);

        ResponseEntity<TransferResponse> retry = idempotency.execute(scope, key, request, TransferResponse.class,
                () -> ResponseEntity.ok(new TransferResponse("t-retry", "SUCCESS")));
        assertThat(retry.getBody().transferId()).isEqualTo("t-retry");
    }

    @Test
    void doesNotCacheServerErrors() {
        String scope = scope();
        String key = newKey();
        idempotency.execute(scope, key, request, TransferResponse.class,
                () -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE scope = ?", Integer.class, scope);
        assertThat(rows).isZero();
        ResponseEntity<TransferResponse> retry = idempotency.execute(scope, key, request, TransferResponse.class,
                () -> ResponseEntity.ok(new TransferResponse("t-ok", "SUCCESS")));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rollsBackActionWritesWhenTheTransactionFails() {
        String scope = scope();
        String key = newKey();
        jdbc.execute("CREATE TABLE IF NOT EXISTS idem_test_effects (id VARCHAR(64) PRIMARY KEY)");
        String effectId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.execute(scope, key, request, TransferResponse.class, () -> {
            jdbc.update("INSERT INTO idem_test_effects (id) VALUES (?)", effectId);
            throw new IllegalStateException("fail after write");
        })).isInstanceOf(IllegalStateException.class);

        Integer effects = jdbc.queryForObject("SELECT count(*) FROM idem_test_effects WHERE id = ?", Integer.class, effectId);
        assertThat(effects).isZero();
    }

    @Test
    void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        String scope = scope();
        String key = newKey();
        AtomicInteger executions = new AtomicInteger();
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        ResponseEntity<TransferResponse> r = idempotency.execute(scope, key, request, TransferResponse.class, () -> {
                            executions.incrementAndGet();
                            sleep(200);
                            return ResponseEntity.ok(new TransferResponse("t-once", "SUCCESS"));
                        });
                        return r.getBody().transferId();
                    } catch (PayverseException e) {
                        return e.errorCode().code();
                    }
                }));
            }
            start.countDown();
            for (Future<String> f : results) {
                assertThat(f.get()).isIn("t-once", CommonErrorCode.REQUEST_IN_PROGRESS.code());
            }
        }
        assertThat(executions).hasValue(1);
    }

    @Test
    void validatesKey() {
        assertThatThrownBy(() -> idempotency.execute(scope(), null, request, TransferResponse.class, () -> ResponseEntity.noContent().build()))
                .isInstanceOfSatisfying(PayverseException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_MISSING));
        assertThatThrownBy(() -> idempotency.execute(scope(), "short", request, TransferResponse.class, () -> ResponseEntity.noContent().build()))
                .isInstanceOfSatisfying(PayverseException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
