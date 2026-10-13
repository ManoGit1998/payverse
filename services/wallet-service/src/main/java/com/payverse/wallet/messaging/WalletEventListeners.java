package com.payverse.wallet.messaging;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.events.payload.UserEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.outbox.ProcessedEventGuard;
import com.payverse.wallet.entity.KycTier;
import com.payverse.wallet.service.WalletService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Wallet creation on registration and KYC tier sync (idempotent on eventId). */
@Component
public class WalletEventListeners {

    private final EventEnvelopeCodec codec;
    private final ProcessedEventGuard guard;
    private final WalletService walletService;

    public WalletEventListeners(EventEnvelopeCodec codec, ProcessedEventGuard guard, WalletService walletService) {
        this.codec = codec;
        this.guard = guard;
        this.walletService = walletService;
    }

    @KafkaListener(topics = Topics.AUTH_USER_REGISTERED, groupId = "wallet-service")
    public void onUserRegistered(String message) {
        EventEnvelope<AuthEvents.UserRegistered> envelope = codec.decode(message, AuthEvents.UserRegistered.class);
        guard.handleOnce(envelope, "wallet-service.create-wallet", e -> walletService.getOrCreate(e.payload().userId()));
    }

    @KafkaListener(topics = Topics.USER_KYC_UPGRADED, groupId = "wallet-service")
    public void onKycUpgraded(String message) {
        EventEnvelope<UserEvents.KycUpgraded> envelope = codec.decode(message, UserEvents.KycUpgraded.class);
        guard.handleOnce(envelope, "wallet-service.kyc-tier",
                e -> walletService.changeTier(e.payload().userId(), KycTier.valueOf(e.payload().toTier())));
    }
}
