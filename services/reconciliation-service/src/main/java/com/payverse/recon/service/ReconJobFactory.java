package com.payverse.recon.service;

import com.payverse.common.events.Amount;
import com.payverse.common.events.EventType;
import com.payverse.common.events.payload.ReconEvents;
import com.payverse.common.outbox.OutboxPublisher;
import com.payverse.recon.client.FeedClient;
import com.payverse.recon.config.ReconProperties;
import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.entity.ReconMismatch;
import com.payverse.recon.entity.ReconRun;
import com.payverse.recon.model.FeedRecord;
import com.payverse.recon.model.IntegrityReport;
import com.payverse.recon.model.ReconPair;
import com.payverse.recon.repository.ReconMismatchRepository;
import com.payverse.recon.repository.ReconRecordStore;
import com.payverse.recon.repository.ReconRunRepository;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Builds the Spring Batch job for one run. Comparison jobs:
 * <ol>
 *   <li><b>extract</b> (tasklet): both feeds into {@code recon_records} (LEFT = the business day, RIGHT = ± tolerance);</li>
 *   <li><b>compare</b> (chunk): a cursor over LEFT ⟗ RIGHT by reference → {@link MismatchClassifier} → mismatches
 *       (+ {@code recon.mismatch-detected} through the outbox, in the chunk's transaction);</li>
 *   <li><b>summarize</b> (tasklet): counts, run COMPLETED.</li>
 * </ol>
 * WALLET_INTEGRITY is a single check step plus summarize. A failed job marks the run FAILED.
 */
@Service
public class ReconJobFactory {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final FeedClient feeds;
    private final ReconRecordStore records;
    private final ReconRunRepository runs;
    private final ReconMismatchRepository mismatches;
    private final OutboxPublisher outbox;
    private final ReconProperties properties;
    private final Clock clock;

    public ReconJobFactory(JobRepository jobRepository, PlatformTransactionManager transactionManager, DataSource dataSource, FeedClient feeds,
                           ReconRecordStore records, ReconRunRepository runs, ReconMismatchRepository mismatches, OutboxPublisher outbox,
                           ReconProperties properties, Clock clock) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.feeds = feeds;
        this.records = records;
        this.runs = runs;
        this.mismatches = mismatches;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    public Job build(ReconRun run) {
        JobBuilder builder = new JobBuilder("recon-" + run.getJob().name().toLowerCase().replace('_', '-'), jobRepository);
        var flow = run.getJob().isComparison()
                ? builder.start(extract(run)).next(compare(run)).next(summarize(run))
                : builder.start(integrity(run)).next(summarize(run));
        return flow.listener(failureListener(run.getId())).build();
    }

    private Step extract(ReconRun run) {
        return new StepBuilder("extract", jobRepository).tasklet((contribution, context) -> {
            Instant from = run.getWindowFrom();
            Instant to = run.getWindowTo();
            Instant rightFrom = from.minus(properties.tolerance());
            Instant rightTo = to.plus(properties.tolerance());
            List<FeedRecord> left;
            List<FeedRecord> right;
            switch (run.getJob()) {
                case PG_VS_LEDGER -> {
                    left = feeds.pgCharges(from, to);
                    right = feeds.addMoneyJournals(rightFrom, rightTo);
                }
                case TRANSFERS_VS_UPI -> {
                    left = feeds.completedTransfers(from, to);
                    right = feeds.switchRecords(rightFrom, rightTo);
                }
                default -> throw new IllegalStateException(run.getJob() + " is not a comparison job");
            }
            records.insert(run.getId(), "LEFT", left, from, to);
            records.insert(run.getId(), "RIGHT", right, from, to);
            contribution.incrementWriteCount(left.size() + right.size());
            return RepeatStatus.FINISHED;
        }, transactionManager).build();
    }

    private Step compare(ReconRun run) {
        JdbcCursorItemReader<ReconPair> reader = new JdbcCursorItemReaderBuilder<ReconPair>()
                .name("pairs")
                .dataSource(dataSource)
                .sql(ReconRecordStore.PAIRS_SQL)
                .preparedStatementSetter(ps -> {
                    ps.setObject(1, run.getId());
                    ps.setObject(2, run.getId());
                })
                .rowMapper((rs, i) -> new ReconPair(rs.getString("reference"), rs.getBigDecimal("left_amount"),
                        (Boolean) rs.getObject("left_in_window"), rs.getBigDecimal("right_amount"), (Boolean) rs.getObject("right_in_window")))
                .build();
        return new StepBuilder("compare", jobRepository).<ReconPair, ReconMismatch>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(pair -> {
                    String type = MismatchClassifier.classify(run.getJob(), pair);
                    return type == null ? null : ReconMismatch.open(run.getId(), run.getJob(), type, pair.reference(), pair.leftAmount(),
                            pair.rightAmount(), clock.instant());
                })
                .writer(writer())
                .build();
    }

    private Step integrity(ReconRun run) {
        return new StepBuilder("check-integrity", jobRepository).tasklet((contribution, context) -> {
            IntegrityReport report = feeds.walletIntegrity();
            List<ReconMismatch> found = new ArrayList<>();
            BiFunction<String, String, ReconMismatch> open = (type, ref) -> ReconMismatch.open(run.getId(), run.getJob(), type, ref, null, null,
                    clock.instant());
            report.walletsOutOfBalance().forEach(w -> found.add(open.apply("WALLET_OUT_OF_BALANCE", w.toString())));
            report.unbalancedJournals().forEach(j -> found.add(open.apply("UNBALANCED_JOURNAL", j.toString())));
            if (report.transferSuspense().signum() != 0) {
                found.add(ReconMismatch.open(run.getId(), run.getJob(), "SUSPENSE_NOT_ZERO", "TRANSFER_SUSPENSE", BigDecimal.ZERO,
                        report.transferSuspense(), clock.instant()));
            }
            writer().write(new Chunk<>(found));
            return RepeatStatus.FINISHED;
        }, transactionManager).build();
    }

    private Step summarize(ReconRun run) {
        return new StepBuilder("summarize", jobRepository).tasklet((contribution, context) -> {
            UUID id = run.getId();
            ReconRun current = runs.findById(id).orElseThrow();
            current.complete(records.count(id, "LEFT", true), records.count(id, "RIGHT", true), records.matched(id),
                    (int) mismatches.countByRunId(id), clock.instant());
            runs.save(current);
            return RepeatStatus.FINISHED;
        }, transactionManager).build();
    }

    private ItemWriter<ReconMismatch> writer() {
        return chunk -> {
            for (ReconMismatch m : chunk) {
                mismatches.save(m);
                outbox.publish(EventType.MISMATCH_DETECTED, m.getRunId().toString(), new ReconEvents.MismatchDetected(m.getRunId(), m.getType(),
                        m.getReference(), amount(m.getExpectedAmount()), amount(m.getActualAmount())));
            }
        };
    }

    private JobExecutionListener failureListener(UUID runId) {
        return new JobExecutionListener() {
            @Override
            public void afterJob(JobExecution execution) {
                if (execution.getStatus() != BatchStatus.COMPLETED) {
                    String reason = execution.getAllFailureExceptions().stream().findFirst()
                            .map(e -> e.getClass().getSimpleName() + ": " + e.getMessage()).orElse(execution.getExitStatus().getExitDescription());
                    new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                            runs.findById(runId).ifPresent(r -> r.fail(reason, clock.instant())));
                }
            }
        };
    }

    private static Amount amount(BigDecimal value) {
        return value == null ? null : new Amount(value, "INR");
    }
}
