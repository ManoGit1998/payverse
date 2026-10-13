package com.payverse.auth.service;

import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;

/** Delivers an OTP to the user (SMS in production; {@link LogOtpSender} in dev). */
public interface OtpSender {

    void send(MobileNumber mobile, String code, OtpPurpose purpose);
}
