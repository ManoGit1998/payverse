package com.payverse.rewards.messaging;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.BillEvents;
import com.payverse.common.events.payload.TransactionEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.events.payload.WalletEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.outbox.ProcessedEventGuard;
import com.payverse.rewards.entity.RewardSource;
import com.payverse.rewards.entity.RuleTrigger;
import com.payverse.rewards.service.ClawbackService;
import com.payverse.rewards.service.ReferralService;
import com.payverse.rewards.service.RuleEngine;
import com.payverse.rewards.service.RuleEngine.QualifyingEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Every event is handled once (ProcessedEventGuard) in one transaction with its effects. */
@Component
public class RewardsEventListeners {

    private static final String GROUP = "rewards-service";

    private final EventEnvelopeCodec codec;
    private final ProcessedEventGuard guard;
    private final RuleEngine engine;
    private final ReferralService referrals;
    private final ClawbackService clawbacks;

    public RewardsEventListeners(EventEnvelopeCodec codec, ProcessedEventGuard guard, RuleEngine engine, ReferralService referrals,
                                 ClawbackService clawbacks) {
        this.codec = codec;
        this.guard = guard;
        this.engine = engine;
        this.referrals = referrals;
        this.clawbacks = clawbacks;
    }

    /** P2P payments earn the payer a reward (the payee does not). */
    @KafkaListener(topics = Topics.TRANSACTION_COMPLETED, groupId = GROUP)
    public void onTransactionCompleted(String message) {
        EventEnvelope<TransactionEvents.TransactionCompleted> envelope = codec.decode(message, TransactionEvents.TransactionCompleted.class);
        guard.handleOnce(envelope, "rewards-service.cashback.transaction", e -> {
            var t = e.payload();
            if ("P2P".equals(t.type()) && t.payeeUserId() != null) {
                engine.evaluate(new QualifyingEvent(RuleTrigger.P2P_TRANSFER, t.payerUserId(), RewardSource.TRANSACTION,
                        t.transactionId().toString(), t.amount().toMoney()));
            }
        });
    }

    @KafkaListener(topics = Topics.WALLET_MONEY_ADDED, groupId = GROUP)
    public void onMoneyAdded(String message) {
        EventEnvelope<WalletEvents.MoneyAdded> envelope = codec.decode(message, WalletEvents.MoneyAdded.class);
        guard.handleOnce(envelope, "rewards-service.cashback.add-money", e -> {
            var m = e.payload();
            String source = m.journalId() != null ? m.journalId().toString() : e.eventId().toString();
            engine.evaluate(new QualifyingEvent(RuleTrigger.ADD_MONEY, m.userId(), RewardSource.ADD_MONEY, source, m.amount().toMoney()));
        });
    }

    @KafkaListener(topics = Topics.BILL_PAYMENT_COMPLETED, groupId = GROUP)
    public void onBillPaymentCompleted(String message) {
        EventEnvelope<BillEvents.BillPaymentCompleted> envelope = codec.decode(message, BillEvents.BillPaymentCompleted.class);
        guard.handleOnce(envelope, "rewards-service.cashback.bill", e -> {
            var b = e.payload();
            engine.evaluate(new QualifyingEvent(RuleTrigger.BILL_PAYMENT, b.userId(), RewardSource.BILL_PAYMENT,
                    b.billPaymentId().toString(), b.amount().toMoney()));
        });
    }

    @KafkaListener(topics = Topics.AUTH_USER_REGISTERED, groupId = GROUP)
    public void onUserRegistered(String message) {
        EventEnvelope<AuthEvents.UserRegistered> envelope = codec.decode(message, AuthEvents.UserRegistered.class);
        guard.handleOnce(envelope, "rewards-service.referral.registered",
                e -> referrals.onUserRegistered(e.payload().userId(), e.payload().referralCode()));
    }

    @KafkaListener(topics = Topics.USER_KYC_UPGRADED, groupId = GROUP)
    public void onKycUpgraded(String message) {
        EventEnvelope<UserEvents.KycUpgraded> envelope = codec.decode(message, UserEvents.KycUpgraded.class);
        guard.handleOnce(envelope, "rewards-service.referral.kyc", e -> referrals.onKycUpgraded(e.payload().userId(), e.payload().toTier()));
    }

    @KafkaListener(topics = Topics.TRANSACTION_REVERSED, groupId = GROUP)
    public void onTransactionReversed(String message) {
        EventEnvelope<TransactionEvents.TransactionReversed> envelope = codec.decode(message, TransactionEvents.TransactionReversed.class);
        guard.handleOnce(envelope, "rewards-service.clawback",
                e -> clawbacks.onTransactionReversed(e.payload().originalTransactionId(), e.payload().reason()));
    }
}
