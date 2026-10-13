package com.payverse.user.messaging;

import com.payverse.common.events.EventEnvelope;
import com.payverse.common.events.Topics;
import com.payverse.common.events.payload.AuthEvents;
import com.payverse.common.outbox.EventEnvelopeCodec;
import com.payverse.common.outbox.ProcessedEventGuard;
import com.payverse.user.service.ProfileService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Creates a profile for every newly registered user (idempotent on eventId). */
@Component
public class UserRegisteredListener {

    static final String CONSUMER = "user-service.profile-on-registration";

    private final EventEnvelopeCodec codec;
    private final ProcessedEventGuard guard;
    private final ProfileService profileService;

    public UserRegisteredListener(EventEnvelopeCodec codec, ProcessedEventGuard guard, ProfileService profileService) {
        this.codec = codec;
        this.guard = guard;
        this.profileService = profileService;
    }

    @KafkaListener(topics = Topics.AUTH_USER_REGISTERED, groupId = "user-service")
    public void onUserRegistered(String message) {
        EventEnvelope<AuthEvents.UserRegistered> envelope = codec.decode(message, AuthEvents.UserRegistered.class);
        guard.handleOnce(envelope, CONSUMER,
                e -> profileService.onUserRegistered(e.payload().userId(), e.payload().mobileMasked()));
    }
}
