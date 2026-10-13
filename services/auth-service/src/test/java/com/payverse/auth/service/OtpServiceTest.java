package com.payverse.auth.service;

import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpChallenge;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.auth.repository.OtpRepository;
import com.payverse.auth.repository.RateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.payverse.auth.service.ServiceTestSupport.CLOCK;
import static com.payverse.auth.service.ServiceTestSupport.PROPERTIES;
import static com.payverse.auth.service.ServiceTestSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpServiceTest {

    static final MobileNumber MOBILE = new MobileNumber("9876543210");

    final Map<OtpPurpose, OtpChallenge> store = new HashMap<>();
    final OtpRepository otpRepository = mock(OtpRepository.class);
    final RateLimitRepository rateLimits = mock(RateLimitRepository.class);
    final OtpSender sender = mock(OtpSender.class);
    OtpService service;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> store.put(((OtpChallenge) inv.getArgument(1)).purpose(), inv.getArgument(1)))
                .when(otpRepository).save(any(), any());
        when(otpRepository.find(any(), any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(1))));
        doAnswer(inv -> store.remove(inv.getArgument(1))).when(otpRepository).delete(any(), any());
        when(rateLimits.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        service = new OtpService(otpRepository, sender, rateLimits, PROPERTIES, CLOCK);
    }

    String requestCode(OtpPurpose purpose) {
        service.request(MOBILE, purpose);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).send(any(), code.capture(), any());
        return code.getValue();
    }

    @Test
    void codeIsSixDigitsAndSingleUse() {
        String code = requestCode(OtpPurpose.LOGIN);
        assertThat(code).hasSize(6).containsOnlyDigits();
        assertThat(store.get(OtpPurpose.LOGIN).codeHash()).doesNotContain(code);
        service.verify(MOBILE, OtpPurpose.LOGIN, code);
        assertError(() -> service.verify(MOBILE, OtpPurpose.LOGIN, code), AuthErrorCode.AUTH_OTP_EXPIRED);
    }

    @Test
    void fiveWrongCodesBurnTheChallenge() {
        String code = requestCode(OtpPurpose.LOGIN);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            assertError(() -> service.verify(MOBILE, OtpPurpose.LOGIN, wrong), AuthErrorCode.AUTH_OTP_INVALID);
        }
        assertError(() -> service.verify(MOBILE, OtpPurpose.LOGIN, wrong), AuthErrorCode.AUTH_OTP_ATTEMPTS_EXCEEDED);
        assertError(() -> service.verify(MOBILE, OtpPurpose.LOGIN, code), AuthErrorCode.AUTH_OTP_EXPIRED);
    }

    @Test
    void purposesAreIsolated() {
        String code = requestCode(OtpPurpose.PIN_RESET);
        assertError(() -> service.verify(MOBILE, OtpPurpose.LOGIN, code), AuthErrorCode.AUTH_OTP_EXPIRED);
    }

    @Test
    void rateLimitIsEnforced() {
        when(rateLimits.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);
        assertError(() -> service.request(MOBILE, OtpPurpose.LOGIN), AuthErrorCode.AUTH_OTP_RATE_LIMITED);
    }
}
