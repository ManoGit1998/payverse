package com.payverse.wallet.service;

import com.payverse.common.error.PayverseException;
import com.payverse.wallet.config.WalletProperties;
import com.payverse.wallet.exception.WalletErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Distributed per-wallet lock (Redis {@code SET NX PX}) held <b>until the surrounding transaction
 * completes</b>, so no other instance can read a balance that is about to change. Re-entrant within a
 * transaction; released with a compare-and-delete script so a lock is never released by a non-owner.
 * {@code @Version} on the wallet remains as a second line of defence.
 */
@Component
public class WalletLock {

    private static final Logger log = LoggerFactory.getLogger(WalletLock.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final WalletProperties properties;

    public WalletLock(StringRedisTemplate redis, WalletProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void lockForTransaction(UUID walletId) {
        lockForTransaction("wallet:" + walletId);
    }

    public void lockForTransaction(String name) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("WalletLock requires an active transaction");
        }
        String key = "lock:" + name;
        if (TransactionSynchronizationManager.hasResource(key)) {
            return; // already held by this transaction
        }
        String token = UUID.randomUUID().toString();
        acquire(key, token);
        TransactionSynchronizationManager.bindResource(key, token);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(key);
                release(key, token);
            }
        });
    }

    private void acquire(String key, String token) {
        Instant deadline = Instant.now().plus(properties.lockWait());
        long backoffMillis = 2;
        while (true) {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, properties.lockLease());
            if (Boolean.TRUE.equals(acquired)) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new PayverseException(WalletErrorCode.WALLET_LOCK_TIMEOUT, null);
            }
            try {
                Thread.sleep(backoffMillis + ThreadLocalRandom.current().nextLong(backoffMillis + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PayverseException(WalletErrorCode.WALLET_LOCK_TIMEOUT, null);
            }
            backoffMillis = Math.min(backoffMillis * 2, 50);
        }
    }

    private void release(String key, String token) {
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (RuntimeException e) {
            log.warn("Could not release {} (it will expire after the lease)", key, e);
        }
    }
}
