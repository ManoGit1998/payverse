package com.payverse.auth.service;

import com.payverse.auth.entity.TransactionPin;
import com.payverse.auth.entity.User;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.auth.model.Pin;
import com.payverse.auth.repository.TransactionPinRepository;
import com.payverse.auth.repository.UserRepository;
import com.payverse.auth.security.PinEncoder;
import com.payverse.common.web.crypto.BlindIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.payverse.auth.service.ServiceTestSupport.CLOCK;
import static com.payverse.auth.service.ServiceTestSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PinServiceTest {

    final Map<UUID, TransactionPin> pinStore = new HashMap<>();
    final TransactionPinRepository pins = mock(TransactionPinRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final OtpService otpService = mock(OtpService.class);
    final AuthEventPublisher events = mock(AuthEventPublisher.class);
    final BlindIndexer blindIndexer = new BlindIndexer(new byte[32]);
    /** Fast fake encoder instead of Argon2. */
    final PinEncoder encoder = new PinEncoder() {
        @Override
        public String encode(Pin pin) {
            return "hash:" + pin.value();
        }

        @Override
        public boolean matches(Pin pin, String hash) {
            return hash.equals("hash:" + pin.value());
        }
    };
    final UUID userId = UUID.randomUUID();
    PinService service;

    @BeforeEach
    void setUp() {
        when(pins.save(any())).thenAnswer(inv -> {
            TransactionPin pin = inv.getArgument(0);
            pinStore.put(pin.getUserId(), pin);
            return pin;
        });
        when(pins.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pins.findById(any())).thenAnswer(inv -> Optional.ofNullable(pinStore.get(inv.getArgument(0))));
        when(pins.existsById(any())).thenAnswer(inv -> pinStore.containsKey(inv.getArgument(0)));
        service = new PinService(pins, users, encoder, otpService, events, blindIndexer, CLOCK);
    }

    @Test
    void setOnceThenVerify() {
        service.setPin(userId, "4826");
        assertThat(service.isSet(userId)).isTrue();
        assertError(() -> service.setPin(userId, "7391"), AuthErrorCode.AUTH_PIN_ALREADY_SET);
        service.verify(userId, "4826");
        assertError(() -> service.verify(userId, "1111"), AuthErrorCode.AUTH_PIN_INCORRECT);
        assertError(() -> service.verify(userId, "abc"), AuthErrorCode.AUTH_PIN_INCORRECT);
    }

    @Test
    void verifyWithoutPinFails() {
        assertError(() -> service.verify(userId, "4826"), AuthErrorCode.AUTH_PIN_NOT_SET);
    }

    @Test
    void lockoutPublishesEventOnce() {
        service.setPin(userId, "4826");
        for (int i = 0; i < 4; i++) {
            assertError(() -> service.verify(userId, "1111"), AuthErrorCode.AUTH_PIN_INCORRECT);
        }
        assertError(() -> service.verify(userId, "1111"), AuthErrorCode.AUTH_PIN_LOCKED);
        assertError(() -> service.verify(userId, "4826"), AuthErrorCode.AUTH_PIN_LOCKED);
        verify(events, times(1)).pinLocked(eq(userId), any());
    }

    @Test
    void changeRequiresCurrentPin() {
        service.setPin(userId, "4826");
        assertError(() -> service.changePin(userId, "1111", "7391"), AuthErrorCode.AUTH_PIN_INCORRECT);
        service.changePin(userId, "4826", "7391");
        service.verify(userId, "7391");
        assertError(() -> service.changePin(userId, "7391", "1234"), AuthErrorCode.AUTH_PIN_TOO_WEAK);
    }

    @Test
    void resetWithOtpReplacesOrCreatesPin() {
        MobileNumber mobile = new MobileNumber("9876543210");
        User user = User.register(mobile, "h", Set.of(), CLOCK.instant());
        when(users.findByMobileHash(anyString())).thenReturn(Optional.of(user));

        service.confirmReset(mobile, "123456", "7391");
        verify(otpService).verify(mobile, OtpPurpose.PIN_RESET, "123456");
        assertThat(pinStore.get(user.getId()).getPinHash()).isEqualTo("hash:7391");

        service.confirmReset(mobile, "654321", "582039");
        assertThat(pinStore.get(user.getId()).getPinHash()).isEqualTo("hash:582039");
    }

    @Test
    void resetRequestOnlySendsForKnownMobiles() {
        MobileNumber unknown = new MobileNumber("9123456789");
        when(users.findByMobileHash(anyString())).thenReturn(Optional.empty());
        service.requestReset(unknown);
        verify(otpService, never()).request(any(), any());
    }
}
