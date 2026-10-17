package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.transaction.client.UserClient;
import com.payverse.transaction.entity.MoneyRequest;
import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.entity.TransferSource;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.repository.MoneyRequestRepository;
import com.payverse.common.money.Money;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/** Request money from someone; they pay (an ordinary saga transfer) or decline; requesters can cancel. */
@Service
public class MoneyRequestService {

    public enum Direction { INCOMING, OUTGOING }

    private final MoneyRequestRepository requests;
    private final PayeeResolver resolver;
    private final UserClient userClient;
    private final TransferService transferService;
    private final TransactionEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MoneyRequestService(MoneyRequestRepository requests, PayeeResolver resolver, UserClient userClient,
                               TransferService transferService, TransactionEventPublisher events,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.requests = requests;
        this.resolver = resolver;
        this.userClient = userClient;
        this.transferService = transferService;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @param payer MOBILE or VPA of the person being asked */
    public MoneyRequest create(UUID requesterUserId, PayeeResolver.Target payer, Money amount, String note, UUID splitBillId) {
        if (payer.type() != PayeeType.MOBILE && payer.type() != PayeeType.VPA) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_PAYEE, "Ask by MOBILE or VPA");
        }
        PayeeResolver.Resolution resolved = resolver.resolve(payer, amount);
        String requesterRef = userClient.summary(requesterUserId).map(UserClient.UserSummary::display).orElse("PayVerse user");
        return tx.execute(status -> {
            MoneyRequest request = requests.save(MoneyRequest.create(requesterUserId, requesterRef, resolved.payee().userId(),
                    resolved.payee().ref(), amount, note, splitBillId, clock.instant()));
            events.moneyRequested(request);
            return request;
        });
    }

    @Transactional
    public Page<MoneyRequest> list(UUID userId, Direction direction, int page, int size) {
        Page<MoneyRequest> result = direction == Direction.INCOMING
                ? requests.findByPayerUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                : requests.findByRequesterUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        result.forEach(r -> r.isPayable(clock.instant()));
        return result;
    }

    /** Pays the request with a normal transfer; the saga marks it PAID when the money lands. */
    public TransferService.TransferResult pay(UUID payerUserId, UUID requestId, String pin, String idempotencyKey, String deviceId) {
        MoneyRequest request = tx.execute(status -> {
            MoneyRequest r = requests.findById(requestId).filter(m -> m.getPayerUserId().equals(payerUserId))
                    .orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_MONEY_REQUEST_NOT_FOUND, null));
            r.ensurePayable(clock.instant());
            return r;
        });
        TransferService.TransferResult result = transferService.transfer(new TransferService.TransferCommand(payerUserId,
                new PayeeResolver.Target(PayeeType.USER, request.getRequesterUserId().toString(), request.getRequesterRef()),
                request.getAmountMoney(), request.getNote(), TransferSource.MONEY_REQUEST, request.getId(), pin, idempotencyKey,
                deviceId));
        tx.executeWithoutResult(status -> requests.findById(requestId).ifPresent(r -> {
            if (r.getTransactionId() == null) {
                r.attachTransaction(result.transaction().getId(), clock.instant());
            }
        }));
        return result;
    }

    @Transactional
    public MoneyRequest decline(UUID payerUserId, UUID requestId) {
        MoneyRequest request = requests.findById(requestId).filter(r -> r.getPayerUserId().equals(payerUserId))
                .orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_MONEY_REQUEST_NOT_FOUND, null));
        request.decline(clock.instant());
        return request;
    }

    @Transactional
    public MoneyRequest cancel(UUID requesterUserId, UUID requestId) {
        MoneyRequest request = requests.findById(requestId).filter(r -> r.getRequesterUserId().equals(requesterUserId))
                .orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_MONEY_REQUEST_NOT_FOUND, null));
        request.cancel(clock.instant());
        return request;
    }

    @Transactional(readOnly = true)
    public MoneyRequest get(UUID requestId) {
        return requests.findById(requestId).orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_MONEY_REQUEST_NOT_FOUND, null));
    }
}
