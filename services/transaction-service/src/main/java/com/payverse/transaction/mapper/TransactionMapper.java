package com.payverse.transaction.mapper;

import com.payverse.common.money.Money;
import com.payverse.transaction.dto.MoneyDto;
import com.payverse.transaction.dto.MoneyRequestResponse;
import com.payverse.transaction.dto.SplitBillResponse;
import com.payverse.transaction.dto.SplitShareResponse;
import com.payverse.transaction.dto.StatusChangeResponse;
import com.payverse.transaction.dto.TransactionResponse;
import com.payverse.transaction.dto.TransferOutcome;
import com.payverse.transaction.dto.TransferResponse;
import com.payverse.transaction.entity.MoneyRequest;
import com.payverse.transaction.entity.MoneyRequestStatus;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatusHistory;
import com.payverse.transaction.service.SplitBillService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.util.UUID;

/** Entities → API responses. Viewer-relative views (SENT/RECEIVED, INCOMING/OUTGOING) take the viewer id. */
@Mapper
public interface TransactionMapper {

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "payee", expression = "java(new com.payverse.transaction.dto.PayeeView(t.getPayeeType(), t.getPayeeRef(), t.getPayeeName()))")
    TransferResponse toTransfer(Transaction t);

    @Mapping(target = "transactionId", source = "id")
    TransferOutcome toOutcome(Transaction t);

    @Mapping(target = "from", source = "fromStatus")
    @Mapping(target = "to", source = "toStatus")
    @Mapping(target = "at", source = "changedAt")
    StatusChangeResponse toResponse(TransactionStatusHistory history);

    default TransactionResponse toResponse(Transaction t, UUID viewer) {
        boolean sent = t.getPayerUserId().equals(viewer);
        String counterparty = sent
                ? (t.getPayeeName() == null ? t.getPayeeRef() : t.getPayeeName() + " (" + t.getPayeeRef() + ")")
                : (t.getPayerRef() == null ? "PayVerse user" : t.getPayerRef());
        return new TransactionResponse(t.getId(), sent ? TransactionResponse.Direction.SENT : TransactionResponse.Direction.RECEIVED,
                counterparty, t.getPayeeType(), money(t.getAmount()), t.getStatus(), sent ? t.getFailureCode() : null, t.getNote(),
                t.getSource(), t.getCreatedAt(), t.getCompletedAt());
    }

    default MoneyRequestResponse toResponse(MoneyRequest r, UUID viewer) {
        boolean incoming = r.getPayerUserId().equals(viewer);
        return new MoneyRequestResponse(r.getId(),
                incoming ? MoneyRequestResponse.Direction.INCOMING : MoneyRequestResponse.Direction.OUTGOING,
                incoming ? (r.getRequesterRef() == null ? "PayVerse user" : r.getRequesterRef()) : r.getPayerRef(),
                money(r.getAmount()), r.getNote(), r.getStatus(), r.getExpiresAt(), r.getTransactionId(), r.getSplitBillId(), r.getCreatedAt());
    }

    default SplitBillResponse toResponse(SplitBillService.SplitView view) {
        Money collected = view.shares().stream().filter(s -> s.getStatus() == MoneyRequestStatus.PAID)
                .map(MoneyRequest::getAmountMoney).reduce(Money.zeroInr(), Money::plus);
        return new SplitBillResponse(view.bill().getId(), MoneyDto.of(view.bill().getTotalMoney()),
                MoneyDto.of(view.bill().getCreatorShareMoney()), MoneyDto.of(collected), view.bill().getNote(),
                view.shares().stream().map(s -> new SplitShareResponse(s.getId(), s.getPayerRef(), money(s.getAmount()), s.getStatus(),
                        s.getTransactionId())).toList(),
                view.bill().getCreatedAt());
    }

    default MoneyDto money(BigDecimal amount) {
        return amount == null ? null : MoneyDto.of(Money.inr(amount));
    }
}
