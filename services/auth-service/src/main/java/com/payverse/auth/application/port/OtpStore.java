package com.payverse.auth.application.port;

import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpChallenge;
import com.payverse.auth.domain.OtpPurpose;

import java.util.Optional;

/** Short-lived OTP challenges (Redis). Entries expire at {@link OtpChallenge#expiresAt()}. */
public interface OtpStore {

    void save(MobileNumber mobile, OtpChallenge challenge);

    Optional<OtpChallenge> find(MobileNumber mobile, OtpPurpose purpose);

    void delete(MobileNumber mobile, OtpPurpose purpose);
}
