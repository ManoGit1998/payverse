package com.payverse.auth.infrastructure.sms;

import com.payverse.auth.application.port.OtpSender;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MOCK SMS provider for local development: writes the OTP to the application log instead of
 * sending an SMS. Active only when {@code payverse.auth.otp.sender=log} (the dev default); the
 * {@code prod} profile sets a different sender so this can never run in production.
 */
@Component
@ConditionalOnProperty(name = "payverse.auth.otp.sender", havingValue = "log", matchIfMissing = true)
class LogOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LogOtpSender.class);

    LogOtpSender() {
        log.warn("LogOtpSender (MOCK) is active: OTPs are written to the log. Never use this in production.");
    }

    @Override
    public void send(MobileNumber mobile, String code, OtpPurpose purpose) {
        log.atInfo()
                .addKeyValue("mock", "sms")
                .addKeyValue("purpose", purpose.name())
                .log("[MOCK SMS] {} OTP for {} is {}", purpose, mobile.masked(), code);
    }
}
