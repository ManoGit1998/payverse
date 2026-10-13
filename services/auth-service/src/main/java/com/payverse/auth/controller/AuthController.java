package com.payverse.auth.controller;

import com.payverse.auth.dto.AuthTokens;
import com.payverse.auth.dto.ChangePinRequest;
import com.payverse.auth.dto.DeviceResponse;
import com.payverse.auth.dto.LoginResult;
import com.payverse.auth.dto.LogoutRequest;
import com.payverse.auth.dto.MeResponse;
import com.payverse.auth.dto.OtpRequest;
import com.payverse.auth.dto.OtpResponse;
import com.payverse.auth.dto.PinResetConfirmRequest;
import com.payverse.auth.dto.PinResetRequest;
import com.payverse.auth.dto.RefreshRequest;
import com.payverse.auth.dto.SetPinRequest;
import com.payverse.auth.dto.TokenResponse;
import com.payverse.auth.dto.VerifyOtpRequest;
import com.payverse.auth.model.DeviceId;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.auth.service.DeviceService;
import com.payverse.auth.service.LoginService;
import com.payverse.auth.service.OtpService;
import com.payverse.auth.service.PinService;
import com.payverse.auth.service.TokenService;
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
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "OTP login, tokens, devices and transaction PIN")
public class AuthController {

    private final OtpService otpService;
    private final LoginService loginService;
    private final TokenService tokenService;
    private final PinService pinService;
    private final DeviceService deviceService;
    private final Clock clock;

    public AuthController(OtpService otpService, LoginService loginService, TokenService tokenService, PinService pinService,
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
    public ResponseEntity<OtpResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
        Instant expiresAt = otpService.request(MobileNumber.parse(request.mobile()), OtpPurpose.LOGIN);
        return ResponseEntity.accepted().body(new OtpResponse(expiresAt, TokenResponse.secondsUntil(expiresAt, clock), "OTP sent"));
    }

    @Operation(summary = "Verify the OTP, register if new, bind the device and issue tokens")
    @PostMapping("/otp/verify")
    public TokenResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request, HttpServletRequest http) {
        LoginResult result = loginService.login(new LoginService.LoginCommand(
                MobileNumber.parse(request.mobile()), request.otp(), new DeviceId(request.deviceId()),
                request.deviceName(), request.referralCode(), ClientIp.of(http)));
        return TokenResponse.of(result.tokens(), result.newUser(), result.pinSet(), clock);
    }

    @Operation(summary = "Rotate the refresh token and get a new access token")
    @PostMapping("/token/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        AuthTokens tokens = tokenService.refresh(request.refreshToken(), new DeviceId(request.deviceId()));
        return TokenResponse.of(tokens, false, pinService.isSet(tokens.userId()), clock);
    }

    @Operation(summary = "End the current session")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request, AuthenticatedUser user) {
        tokenService.logout(user.userId(), request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Who am I")
    @GetMapping("/me")
    public MeResponse me(AuthenticatedUser user) {
        return new MeResponse(user.userId(), user.roles().stream().sorted().toList(), user.deviceId(), pinService.isSet(user.userId()));
    }

    @Operation(summary = "List bound devices")
    @GetMapping("/devices")
    public List<DeviceResponse> devices(AuthenticatedUser user) {
        return deviceService.activeDevices(user.userId()).stream()
                .map(device -> DeviceResponse.of(device, user.deviceId()))
                .toList();
    }

    @Operation(summary = "Revoke a device and all its sessions")
    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<Void> revokeDevice(@PathVariable String deviceId, AuthenticatedUser user) {
        deviceService.revoke(user.userId(), new DeviceId(deviceId));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Set the transaction PIN (first time)")
    @PostMapping("/pin")
    public ResponseEntity<Void> setPin(@Valid @RequestBody SetPinRequest request, AuthenticatedUser user) {
        pinService.setPin(user.userId(), request.pin());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Change the transaction PIN")
    @PostMapping("/pin/change")
    public ResponseEntity<Void> changePin(@Valid @RequestBody ChangePinRequest request, AuthenticatedUser user) {
        pinService.changePin(user.userId(), request.currentPin(), request.newPin());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send a PIN-reset OTP (always 202, to avoid account enumeration)")
    @PostMapping("/pin/reset/request")
    public ResponseEntity<Void> requestPinReset(@Valid @RequestBody PinResetRequest request) {
        pinService.requestReset(MobileNumber.parse(request.mobile()));
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Reset the PIN with the OTP")
    @PostMapping("/pin/reset/confirm")
    public ResponseEntity<Void> confirmPinReset(@Valid @RequestBody PinResetConfirmRequest request) {
        pinService.confirmReset(MobileNumber.parse(request.mobile()), request.otp(), request.newPin());
        return ResponseEntity.noContent().build();
    }
}
