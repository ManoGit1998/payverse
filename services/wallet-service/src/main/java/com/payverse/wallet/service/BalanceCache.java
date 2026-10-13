package com.payverse.wallet.service;

import com.payverse.wallet.config.WalletProperties;
import com.payverse.wallet.dto.WalletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Redis cache of the wallet summary (balance, status, limits). Every change evicts the entry
 * <b>after commit</b>, so a reader can never cache a balance from an uncommitted transaction.
 * The ledger stays the source of truth; Redis failures degrade to DB reads.
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final WalletProperties properties;

    public BalanceCache(StringRedisTemplate redis, JsonMapper jsonMapper, WalletProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    public Optional<WalletResponse> get(UUID userId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(userId)))
                    .map(json -> jsonMapper.readValue(json, WalletResponse.class));
        } catch (RuntimeException e) {
            log.warn("Wallet cache read failed; falling back to the database", e);
            return Optional.empty();
        }
    }

    public void put(WalletResponse summary) {
        try {
            redis.opsForValue().set(key(summary.userId()), jsonMapper.writeValueAsString(summary), properties.summaryCacheTtl());
        } catch (RuntimeException e) {
            log.warn("Wallet cache write failed", e);
        }
    }

    public void evictAfterCommit(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    evict(userId);
                }
            });
        } else {
            evict(userId);
        }
    }

    private void evict(UUID userId) {
        try {
            redis.delete(key(userId));
        } catch (RuntimeException e) {
            log.warn("Wallet cache eviction failed (entry expires after its TTL)", e);
        }
    }

    private static String key(UUID userId) {
        return "wallet:summary:" + userId;
    }
}
