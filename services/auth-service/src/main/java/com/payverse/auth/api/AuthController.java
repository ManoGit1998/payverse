package com.payverse.auth.api;

import com.payverse.auth.api.AuthDtos.ChangePinRequest;
import com.payverse.auth.api.AuthDtos.DeviceResponse;
import com.payverse.auth.api.AuthDtos.LogoutRequest;
import com.payverse.auth.api.AuthDtos.MeResponse;
import com.payverse.auth.api.AuthDtos.OtpRequest;
import com.payverse.auth.api.AuthDtos.OtpResponse;
import com.payverse.auth.api.AuthDtos.PinResetConfirmRequest;
import com.payverse.auth.api.AuthDtos.PinResetRequest;
import com.payverse.auth.api.AuthDtos.RefreshRequest;
import com.payverse.auth.api.AuthDtos.SetPinRequest;
import com.payverse.auth.api.AuthDtos.TokenResponse;
import com.payverse.auth.api.AuthDtos.VerifyOtpRequest;
import com.payverse.auth.application.AuthTokens;
import com.payverse.auth.application.DeviceService;
import com.payverse.auth.application.LoginService;
import com.payverse.auth.application.OtpService;
import com.payverse.auth.application.PinService;
import com.payverse.auth.application.TokenService;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpPurpose;
import com.payverse.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "OTP login, tokens, devices and transaction PIN")
class AuthController {

    private final OtpService otpService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final PinService pinService;
    private final DeviceService deviceService;
    private final Clock clock;

    AuthController(OtpService otpService, LoginService loginService, TokenService tokenService, PinService pinService,
                   DeviceService deviceService, Clock clock) {
        this.otpService = otpService;
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.pinService = pinService;
        this.deviceService = deviceService;
        this.clock = clock;
    }

    @Operation(summary = "Send a login OTP (sign-up and login are the same flow)")
    @PostMapping("/otp/request")
    ResponseEntity<OtpResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
        OtpService.OtpIssued issued = otpService.request(MobileNumber.parse(request.mobile()), OtpPurpose.LOGIN);
        return ResponseEntity.accepted().body(new OtpResponse(issued.expiresAt(),
                AuthDtos.secondsUntil(issued.expiresAt(), clock), "OTP sent"));
    }

    @Operation(summary = "Verify the OTP, register if new, bind the device and issue tokens")
    @PostMapping("/otp/verify")
    TokenResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request, HttpServletRequest http) {
        LoginService.LoginResult result = loginService.login(new LoginService.LoginCommand(
                MobileNumber.parse(request.mobile()), request.otp(), new DeviceId(request.deviceId()),
                request.deviceName(), request.referralCode(), ClientIp.of(http)));
        return TokenResponse.of(result.tokens(), result.newUser(), result.pinSet(), clock);
    }

    @Operation(summary = "Rotate the refresh token and get a new access token")
    @PostMapping("/token/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        AuthTokens tokens = tokenService.refresh(request.refreshToken(), new DeviceId(request.deviceId()));
        return TokenResponse.of(tokens, false, pinService.isSet(tokens.userId()), clock);
    }

    @Operation(summary = "End the current session")
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request, AuthenticatedUser user) {
        tokenService.logout(user.userId(), request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Who am I")
    @GetMapping("/me")
    MeResponse me(AuthenticatedUser user) {
        return new MeResponse(user.userId(), user.roles().stream().sorted().toList(), user.deviceId(), pinService.isSet(user.userId()));
    }

    @Operation(summary = "List bound devices")
    @GetMapping("/devices")
    List<DeviceResponse> devices(AuthenticatedUser user) {
        return deviceService.activeDevices(user.userId()).stream()
                .map(device -> DeviceResponse.of(device, user.deviceId()))
                .toList();
    }

    @Operation(summary = "Revoke a device and all its sessions")
    @DeleteMapping("/devices/{deviceId}")
    ResponseEntity<Void> revokeDevice(@PathVariable String deviceId, AuthenticatedUser user) {
        deviceService.revoke(user.userId(), new DeviceId(deviceId));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Set the transaction PIN (first time)")
    @PostMapping("/pin")
    ResponseEntity<Void> setPin(@Valid @RequestBody SetPinRequest request, AuthenticatedUser user) {
        pinService.setPin(user.userId(), request.pin());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Change the transaction PIN")
    @PostMapping("/pin/change")
    ResponseEntity<Void> changePin(@Valid @RequestBody ChangePinRequest request, AuthenticatedUser user) {
        pinService.changePin(user.userId(), request.currentPin(), request.newPin());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send a PIN-reset OTP (always 202, to avoid account enumeration)")
    @PostMapping("/pin/reset/request")
    ResponseEntity<Void> requestPinReset(@Valid @RequestBody PinResetRequest request) {
        pinService.requestReset(MobileNumber.parse(request.mobile()));
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Reset the PIN with the OTP")
    @PostMapping("/pin/reset/confirm")
    ResponseEntity<Void> confirmPinReset(@Valid @RequestBody PinResetConfirmRequest request) {
        pinService.confirmReset(MobileNumber.parse(request.mobile()), request.otp(), request.newPin());
        return ResponseEntity.noContent().build();
    }
}
