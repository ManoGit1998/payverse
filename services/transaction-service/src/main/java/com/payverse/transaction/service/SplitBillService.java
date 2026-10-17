package com.payverse.transaction.service;

import com.payverse.common.error.PayverseException;
import com.payverse.common.money.Money;
import com.payverse.transaction.client.UserClient;
import com.payverse.transaction.config.TransactionProperties;
import com.payverse.transaction.entity.MoneyRequest;
import com.payverse.transaction.entity.PayeeType;
import com.payverse.transaction.entity.SplitBill;
import com.payverse.transaction.exception.TransactionErrorCode;
import com.payverse.transaction.repository.MoneyRequestRepository;
import com.payverse.transaction.repository.SplitBillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Split a bill equally ({@link Money#allocate}: shares always add up to the total exactly) or with
 * custom amounts. Every participant is resolved first; then the bill and one money request per
 * participant are saved in a single transaction (all or nothing).
 */
@Service
public class SplitBillService {

    public record Share(PayeeType type, String value, Money amount) {
    }

    public record Allocation(Money creatorShare, List<Money> participantShares) {
    }

    public record SplitView(SplitBill bill, List<MoneyRequest> shares) {
    }

    private final SplitBillRepository bills;
    private final MoneyRequestRepository requests;
    private final PayeeResolver resolver;
    private final UserClient userClient;
    private final TransactionEventPublisher events;
    private final TransactionProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SplitBillService(SplitBillRepository bills, MoneyRequestRepository requests, PayeeResolver resolver, UserClient userClient,
                            TransactionEventPublisher events, TransactionProperties properties,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.bills = bills;
        this.requests = requests;
        this.resolver = resolver;
        this.userClient = userClient;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public SplitView create(UUID creatorUserId, Money total, String note, boolean includeMe, List<Share> participants) {
        Allocation allocation = allocate(total, includeMe, participants, properties.maxSplitParticipants());
        List<PayeeResolver.Resolution> resolved = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            Share p = participants.get(i);
            if (p.type() != PayeeType.MOBILE && p.type() != PayeeType.VPA) {
                throw new PayverseException(TransactionErrorCode.TXN_INVALID_SPLIT, "Participants are added by MOBILE or VPA");
            }
            resolved.add(resolver.resolve(new PayeeResolver.Target(p.type(), p.value(), null), allocation.participantShares().get(i)));
        }
        String creatorRef = userClient.summary(creatorUserId).map(UserClient.UserSummary::display).orElse("PayVerse user");
        String requestNote = note == null || note.isBlank() ? "Split bill" : note;

        return tx.execute(status -> {
            SplitBill bill = bills.save(SplitBill.create(creatorUserId, total, allocation.creatorShare(), note, clock.instant()));
            List<MoneyRequest> shares = new ArrayList<>();
            for (int i = 0; i < resolved.size(); i++) {
                var payee = resolved.get(i).payee();
                MoneyRequest share = requests.save(MoneyRequest.create(creatorUserId, creatorRef, payee.userId(), payee.ref(),
                        allocation.participantShares().get(i), requestNote, bill.getId(), clock.instant()));
                events.moneyRequested(share);
                shares.add(share);
            }
            return new SplitView(bill, shares);
        });
    }

    @Transactional(readOnly = true)
    public SplitView get(UUID viewer, UUID splitId) {
        SplitBill bill = bills.findById(splitId)
                .orElseThrow(() -> new PayverseException(TransactionErrorCode.TXN_SPLIT_NOT_FOUND, null));
        List<MoneyRequest> shares = requests.findBySplitBillIdOrderByCreatedAt(splitId);
        boolean visible = bill.getCreatorUserId().equals(viewer) || shares.stream().anyMatch(s -> s.getPayerUserId().equals(viewer));
        if (!visible) {
            throw new PayverseException(TransactionErrorCode.TXN_SPLIT_NOT_FOUND, null);
        }
        return new SplitView(bill, shares);
    }

    /**
     * Equal: {@code total.allocate(n + creator)}, the creator's share first (leftover paise go to the
     * first shares). Custom: the given amounts; the creator keeps the remainder, which must be zero
     * when the creator is not part of the split.
     */
    public static Allocation allocate(Money total, boolean includeMe, List<Share> participants, int maxParticipants) {
        if (participants.isEmpty() || participants.size() > maxParticipants) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_SPLIT, "1-" + maxParticipants + " participants");
        }
        Set<String> seen = new HashSet<>();
        for (Share p : participants) {
            if (!seen.add(p.type() + ":" + p.value().trim().toLowerCase(Locale.ROOT))) {
                throw new PayverseException(TransactionErrorCode.TXN_INVALID_SPLIT, "Duplicate participant " + p.value());
            }
        }
        long withAmount = participants.stream().filter(p -> p.amount() != null).count();
        if (withAmount == 0) {
            List<Money> parts = total.allocate(participants.size() + (includeMe ? 1 : 0));
            return includeMe
                    ? new Allocation(parts.getFirst(), List.copyOf(parts.subList(1, parts.size())))
                    : new Allocation(Money.zeroInr(), parts);
        }
        if (withAmount != participants.size()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_SPLIT, "Give an amount for every participant or for none");
        }
        List<Money> custom = participants.stream().map(Share::amount).toList();
        Money creatorShare = total.minus(custom.stream().reduce(Money.zeroInr(), Money::plus));
        if (creatorShare.isNegative()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_SPLIT, "Shares exceed the total");
        }
        if (!includeMe && !creatorShare.isZero()) {
            throw new PayverseException(TransactionErrorCode.TXN_INVALID_SPLIT, "Shares must add up to the total");
        }
        return new Allocation(creatorShare, custom);
    }
}
