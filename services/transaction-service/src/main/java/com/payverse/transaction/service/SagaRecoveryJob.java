package com.payverse.transaction.service;

import com.payverse.transaction.config.TransactionProperties;
import com.payverse.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finishes sagas that stopped half-way: steps that hit a timeout/outage (scheduled retry), and
 * transfers whose runner died (no update for {@code staleAfter}). Every step is idempotent, so
 * re-running is always safe.
 */
@Component
public class SagaRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);

    private final TransactionRepository transactions;
    private final TransferSaga saga;
    private final TransactionProperties properties;
    private final Clock clock;

    public SagaRecoveryJob(TransactionRepository transactions, TransferSaga saga, TransactionProperties properties, Clock clock) {
        this.transactions = transactions;
        this.saga = saga;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payverse.transaction.recovery-interval-ms:10000}",
            initialDelayString = "${payverse.transaction.recovery-interval-ms:10000}")
    public void recover() {
        Instant now = clock.instant();
        List<UUID> due = transactions.findDueForRecovery(now, now.minus(properties.staleAfter()), PageRequest.of(0, 50));
        for (UUID id : due) {
            try {
                saga.run(id);
            } catch (RuntimeException e) {
                log.error("Saga recovery failed for {}", id, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Saga recovery processed {} transaction(s)", due.size());
        }
    }
}
