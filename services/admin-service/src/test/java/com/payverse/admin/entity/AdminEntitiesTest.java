package com.payverse.admin.entity;

import com.payverse.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminEntitiesTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void disputeLifecycle() {
        assertThatThrownBy(() -> Dispute.open(UUID.randomUUID(), UUID.randomUUID(), " ", NOW)).isInstanceOf(IllegalArgumentException.class);
        Dispute d = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(600), NOW);
        assertThat(d.getReason()).hasSize(500);
        d.assign("support-1", NOW);
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.IN_REVIEW);
        assertThatThrownBy(() -> d.resolve(DisputeOutcome.REFUND, "s", null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.resolve(DisputeOutcome.REJECTED, "s", null, UUID.randomUUID(), NOW)).isInstanceOf(IllegalArgumentException.class);
        d.resolve(DisputeOutcome.REJECTED, "support-2", "duplicate", null, NOW);
        assertThat(d.getAssignee()).isEqualTo("support-1");
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThatThrownBy(() -> d.assign("x", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> d.resolve(DisputeOutcome.REJECTED, "x", null, null, NOW)).isInstanceOf(IllegalStateException.class);

        Dispute unassigned = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "wrong amount", NOW);
        unassigned.resolve(DisputeOutcome.REFUND, "support-1", "y".repeat(600), UUID.randomUUID(), NOW);
        assertThat(unassigned.getAssignee()).isEqualTo("support-1");
        assertThat(unassigned.getResolutionNote()).hasSize(500);
    }

    @Test
    void refundMakerChecker() {
        RefundRequest r = RefundRequest.request(UUID.randomUUID(), Money.inr("10"), null, null, "support-1", NOW);
        assertThat(r.getReason()).isEqualTo("refund");
        assertThat(r.isExecutable(NOW)).isFalse();
        assertThatThrownBy(() -> r.approve("support-1", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.completed(NOW)).isInstanceOf(IllegalStateException.class);
        r.approve("admin-1", NOW);
        assertThat(r.isExecutable(NOW)).isTrue();
        r.retryLater("DOWNSTREAM_UNAVAILABLE", NOW);
        assertThat(r.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(r.isExecutable(NOW)).isFalse();
        assertThat(r.isExecutable(NOW.plusSeconds(2))).isTrue();
        r.completed(NOW);
        assertThat(r.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(r.getAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> r.reject("admin-2", NOW)).isInstanceOf(IllegalStateException.class);

        RefundRequest failed = RefundRequest.request(UUID.randomUUID(), Money.inr("10"), "z".repeat(300), null, "a", NOW);
        assertThat(failed.getReason()).hasSize(200);
        failed.approve("b", NOW);
        failed.failed("WALLET_INSUFFICIENT_BALANCE", NOW);
        assertThat(failed.getStatus()).isEqualTo(RefundStatus.FAILED);

        RefundRequest rejected = RefundRequest.request(UUID.randomUUID(), Money.inr("10"), "r", null, "a", NOW);
        rejected.reject("b", NOW);
        assertThat(rejected.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(rejected.getMoney()).isEqualTo(Money.inr("10"));
        assertThatThrownBy(() -> RefundRequest.request(UUID.randomUUID(), Money.inr("0"), "r", null, "a", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
