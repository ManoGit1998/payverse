package com.payverse.auth.api;

import com.payverse.auth.api.AuthDtos.UserLookupRequest;
import com.payverse.auth.api.AuthDtos.UserLookupResponse;
import com.payverse.auth.api.AuthDtos.VerifyPinRequest;
import com.payverse.auth.api.AuthDtos.VerifyPinResponse;
import com.payverse.auth.application.PinService;
import com.payverse.auth.application.UserLookupService;
import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.common.error.PayverseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API (role SERVICE required by common-security; never routed by the gateway). */
@RestController
@RequestMapping("/internal/auth")
@Tag(name = "Internal")
class InternalAuthController {

    private final PinService pinService;
    private final UserLookupService userLookup;

    InternalAuthController(PinService pinService, UserLookupService userLookup) {
        this.pinService = pinService;
        this.userLookup = userLookup;
    }

    @Operation(summary = "Verify a user's transaction PIN (counts failures, locks after 5)")
    @PostMapping("/pin/verify")
    VerifyPinResponse verifyPin(@Valid @RequestBody VerifyPinRequest request) {
        pinService.verify(request.userId(), request.pin());
        return new VerifyPinResponse(true);
    }

    @Operation(summary = "Resolve an active user by mobile number")
    @PostMapping("/users/lookup")
    UserLookupResponse lookup(@Valid @RequestBody UserLookupRequest request) {
        return userLookup.findActiveByMobile(MobileNumber.parse(request.mobile()))
                .map(user -> new UserLookupResponse(user.id(), user.mobile().masked()))
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_USER_NOT_FOUND, null));
    }
}
