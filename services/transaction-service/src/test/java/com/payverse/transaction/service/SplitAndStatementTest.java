package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransferSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitAndStatementTest {

    static SplitBillService.Share share(String vpa, String amount) {
        return new SplitBillService.Share(PayeeType.VPA, vpa, amount == null ? null : Money.inr(amount));
    }

    @Test
    void equalSplitIncludingCreatorAddsUpExactly() {
        var allocation = SplitBillService.allocate(Money.inr("100"), true, List.of(share("a1@payverse", null), share("b1@payverse", null)), 20);
        assertThat(allocation.creatorShare()).isEqualTo(Money.inr("33.34"));
        assertThat(allocation.participantShares()).containsExactly(Money.inr("33.33"), Money.inr("33.33"));
    }

    @Test
    void equalSplitWithoutCreator() {
        var allocation = SplitBillService.allocate(Money.inr("100"), false, List.of(share("a1@payverse", null), share("b1@payverse", null)), 20);
        assertThat(allocation.creatorShare()).isEqualTo(Money.zeroInr());
        assertThat(allocation.participantShares()).containsExactly(Money.inr("50"), Money.inr("50"));
    }

    @Test
    void customSplit() {
        var allocation = SplitBillService.allocate(Money.inr("100"), true, List.of(share("a1@payverse", "30"), share("b1@payverse", "45.50")), 20);
        assertThat(allocation.creatorShare()).isEqualTo(Money.inr("24.50"));
        assertThatThrownBy(() -> SplitBillService.allocate(Money.inr("100"), false, List.of(share("a1@payverse", "30")), 20))
                .hasMessageContaining("add up");
        assertThatThrownBy(() -> SplitBillService.allocate(Money.inr("10"), true, List.of(share("a1@payverse", "30")), 20))
                .hasMessageContaining("exceed");
        assertThatThrownBy(() -> SplitBillService.allocate(Money.inr("10"), true, List.of(share("a1@payverse", "3"), share("b1@payverse", null)), 20))
                .hasMessageContaining("every participant");
    }

    @Test
    void participantRules() {
        assertThatThrownBy(() -> SplitBillService.allocate(Money.inr("10"), true, List.of(share("a1@payverse", null), share("A1@PayVerse", null)), 20))
                .isInstanceOf(PayverseException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> SplitBillService.allocate(Money.inr("10"), true, List.of(), 20)).isInstanceOf(PayverseException.class);
        assertThatThrownBy(() -> SplitBillService.allocate(Money.inr("10"), true, List.of(share("a1@payverse", null), share("b1@payverse", null)), 1))
                .isInstanceOf(PayverseException.class);
    }

    @Test
    void statementsMaskAndEscape() {
        UUID viewer = UUID.randomUUID();
        Transaction sent = Transaction.initiate(viewer, "Me", new Transaction.Payee(UUID.randomUUID(), PayeeType.VPA, "ravi@payverse", "Ravi"),
                Money.inr("99.90"), "=HYPERLINK(\"x\")", TransferSource.DIRECT, null, "k1-00000001", "h", Instant.parse("2026-10-17T05:00:00Z"));
        StatementService service = new StatementService();
        String csv = new String(service.csv(viewer, List.of(sent)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("date,description,type,amount,currency,status,transaction_id\n")
                .contains("DEBIT,99.90,INR").contains("To ravi@payverse")
                .contains("\"To ravi@payverse — =HYPERLINK(\"\"x\"\")\"");
        assertThat(StatementService.escape("=1+1")).isEqualTo("\"'=1+1\"");
        byte[] pdf = service.pdf(viewer, List.of(sent), Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"));
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
