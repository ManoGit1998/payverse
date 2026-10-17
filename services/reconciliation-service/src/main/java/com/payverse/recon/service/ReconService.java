package com.payverse.recon.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.web.audit.AuditLogger;
import com.payverse.recon.entity.MismatchStatus;
import com.payverse.recon.entity.ReconJob;
import com.payverse.recon.entity.ReconMismatch;
import com.payverse.recon.entity.ReconRun;
import com.payverse.recon.exception.ReconErrorCode;
import com.payverse.recon.repository.ReconMismatchRepository;
import com.payverse.recon.repository.ReconRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Starts runs (on demand or nightly), and serves runs and mismatches to admins. */
@Service
public class ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    private final ReconRunRepository runs;
    private final ReconMismatchRepository mismatches;
    private final ReconJobFactory jobs;
    private final JobOperator jobOperator;
    private final Executor executor;
    private final AuditLogger audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReconService(ReconRunRepository runs, ReconMismatchRepository mismatches, ReconJobFactory jobs, JobOperator jobOperator,
                        @Qualifier("reconExecutor") Executor executor, AuditLogger audit, PlatformTransactionManager transactionManager,
                        Clock clock) {
        this.runs = runs;
        this.mismatches = mismatches;
        this.jobs = jobs;
        this.jobOperator = jobOperator;
        this.executor = executor;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Creates the run and executes its batch job in the background; poll the run for the result. */
    public ReconRun trigger(ReconJob job, LocalDate businessDate, String actor) {
        ReconRun run = tx.execute(status -> {
            ReconRun created = runs.save(ReconRun.start(job, businessDate, actor, clock.instant()));
            audit.record(actor, "RECON_RUN_STARTED", "ReconRun", created.getId().toString(), Map.of("job", job.name(), "date", businessDate.toString()));
            return created;
        });
        executor.execute(() -> execute(run));
        return run;
    }

    void execute(ReconRun run) {
        try {
            JobExecution execution = jobOperator.start(jobs.build(run), new JobParametersBuilder()
                    .addString("runId", run.getId().toString())
                    .addString("businessDate", run.getBusinessDate().toString())
                    .toJobParameters());
            tx.executeWithoutResult(status -> runs.findById(run.getId()).ifPresent(r -> r.attachExecution(execution.getId())));
            log.info("Recon run {} ({} {}) finished: {}", run.getId(), run.getJob(), run.getBusinessDate(), execution.getStatus());
        } catch (Exception e) {
            log.error("Recon run {} could not start", run.getId(), e);
            tx.executeWithoutResult(status -> runs.findById(run.getId()).ifPresent(r -> r.fail(e.getMessage(), clock.instant())));
        }
    }

    /** Every night: all jobs for the previous IST day. */
    @Scheduled(cron = "${payverse.recon.nightly-cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        LocalDate yesterday = LocalDate.now(clock.withZone(ReconRun.IST)).minusDays(1);
        for (ReconJob job : ReconJob.values()) {
            trigger(job, yesterday, "scheduler");
        }
    }

    @Transactional(readOnly = true)
    public Page<ReconRun> runs(ReconJob job, int page, int size) {
        return job == null ? runs.findAllByOrderByStartedAtDesc(PageRequest.of(page, size))
                : runs.findByJobOrderByStartedAtDesc(job, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public ReconRun run(UUID id) {
        return runs.findById(id).orElseThrow(() -> new PayverseException(ReconErrorCode.RECON_RUN_NOT_FOUND, null));
    }

    @Transactional(readOnly = true)
    public Page<ReconMismatch> mismatches(UUID runId, int page, int size) {
        run(runId);
        return mismatches.findByRunIdOrderByTypeAscReferenceAsc(runId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<ReconMismatch> open(int page, int size) {
        return mismatches.findByStatusOrderByCreatedAtDesc(MismatchStatus.OPEN, PageRequest.of(page, size));
    }

    @Transactional
    public ReconMismatch resolve(String actor, UUID mismatchId, String note) {
        ReconMismatch m = mismatches.findById(mismatchId)
                .orElseThrow(() -> new PayverseException(ReconErrorCode.RECON_MISMATCH_NOT_FOUND, null));
        if (m.getStatus() != MismatchStatus.OPEN) {
            throw new PayverseException(ReconErrorCode.RECON_MISMATCH_ALREADY_RESOLVED, null);
        }
        m.resolve(actor, note, clock.instant());
        audit.record(actor, "RECON_MISMATCH_RESOLVED", "ReconMismatch", m.getId().toString(), Map.of("type", m.getType(), "reference", m.getReference()));
        return m;
    }
}
