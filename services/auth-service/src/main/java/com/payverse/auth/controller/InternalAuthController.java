package com.payverse.auth.controller;

import com.payverse.auth.dto.UserLookupRequest;
import com.payverse.auth.dto.UserLookupResponse;
import com.payverse.auth.dto.VerifyPinRequest;
import com.payverse.auth.dto.VerifyPinResponse;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.service.PinService;
import com.payverse.auth.service.UserLookupService;
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
public class InternalAuthController {

    private final PinService pinService;
    private final UserLookupService userLookup;

    public InternalAuthController(PinService pinService, UserLookupService userLookup) {
        this.pinService = pinService;
        this.userLookup = userLookup;
    }

    @Operation(summary = "Verify a user's transaction PIN (counts failures, locks after 5)")
    @PostMapping("/pin/verify")
    public VerifyPinResponse verifyPin(@Valid @RequestBody VerifyPinRequest request) {
        pinService.verify(request.userId(), request.pin());
        return new VerifyPinResponse(true);
    }

    @Operation(summary = "Resolve an active user by mobile number")
    @PostMapping("/users/lookup")
    public UserLookupResponse lookup(@Valid @RequestBody UserLookupRequest request) {
        return userLookup.findActiveByMobile(MobileNumber.parse(request.mobile()))
                .map(user -> new UserLookupResponse(user.getId(), user.getMobileNumber().masked()))
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_USER_NOT_FOUND, null));
    }
}
