package com.payverse.recon.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconEntitiesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void runWindowIsTheIstDay() {
        ReconRun run = ReconRun.start(ReconJob.PG_VS_LEDGER, LocalDate.of(2026, 10, 16), "admin", NOW);
        assertThat(run.getWindowFrom()).isEqualTo(Instant.parse("2026-10-15T18:30:00Z"));
        assertThat(run.getWindowTo()).isEqualTo(Instant.parse("2026-10-16T18:30:00Z"));
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        run.attachExecution(7L);
        run.complete(4, 4, 2, 3, NOW);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getMismatchCount()).isEqualTo(3);
        run.fail("late failure", NOW);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);

        ReconRun failed = ReconRun.start(ReconJob.WALLET_INTEGRITY, LocalDate.of(2026, 10, 16), "admin", NOW);
        failed.fail("x".repeat(600), NOW);
        assertThat(failed.getError()).hasSize(500);
        ReconRun unknown = ReconRun.start(ReconJob.WALLET_INTEGRITY, LocalDate.of(2026, 10, 16), "admin", NOW);
        unknown.fail(null, NOW);
        assertThat(unknown.getError()).isEqualTo("unknown error");
    }

    @Test
    void jobsKnowTheirMismatchTypes() {
        assertThat(ReconJob.PG_VS_LEDGER.isComparison()).isTrue();
        assertThat(ReconJob.WALLET_INTEGRITY.isComparison()).isFalse();
        assertThat(ReconJob.TRANSFERS_VS_UPI.rightOnlyType()).isEqualTo("ORPHAN_SWITCH_RECORD");
    }

    @Test
    void mismatchResolvesOnce() {
        ReconMismatch m = ReconMismatch.open(UUID.randomUUID(), ReconJob.PG_VS_LEDGER, "AMOUNT_MISMATCH", "R1", BigDecimal.ONE, BigDecimal.TEN, NOW);
        m.resolve("admin", "y".repeat(600), NOW);
        assertThat(m.getStatus()).isEqualTo(MismatchStatus.RESOLVED);
        assertThat(m.getNote()).hasSize(500);
        assertThatThrownBy(() -> m.resolve("admin", "again", NOW)).isInstanceOf(IllegalStateException.class);
    }
}
