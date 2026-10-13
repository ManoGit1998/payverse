package com.payverse.auth.application.port;

import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpPurpose;

/** Delivers an OTP to the user (SMS in production; {@code LogOtpSender} in dev). */
public interface OtpSender {

    void send(MobileNumber mobile, String code, OtpPurpose purpose);
}
