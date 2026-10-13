package com.payverse.auth.application;

import com.payverse.auth.application.port.AuthEventPublisher;
import com.payverse.auth.application.port.DeviceRepository;
import com.payverse.auth.application.port.TransactionPinRepository;
import com.payverse.auth.application.port.UserRepository;
import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpPurpose;
import com.payverse.auth.domain.Role;
import com.payverse.auth.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** OTP login: verifies the code, registers first-time users, binds the device and issues tokens. */
@Service
public class LoginService {

    private final OtpService otpService;
    private final UserRepository users;
    private final DeviceRepository devices;
    private final TransactionPinRepository pins;
    private final TokenService tokenService;
    private final AuthEventPublisher events;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginService(OtpService otpService, UserRepository users, DeviceRepository devices,
                        TransactionPinRepository pins, TokenService tokenService, AuthEventPublisher events,
                        AuthProperties properties, Clock clock) {
        this.otpService = otpService;
        this.users = users;
        this.devices = devices;
        this.pins = pins;
        this.tokenService = tokenService;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public record LoginCommand(MobileNumber mobile, String otp, DeviceId deviceId, String deviceName,
                               String referralCode, String ipAddress) {
    }

    public record LoginResult(AuthTokens tokens, boolean newUser, boolean pinSet) {
    }

    @Transactional
    public LoginResult login(LoginCommand command) {
        otpService.verify(command.mobile(), OtpPurpose.LOGIN, command.otp());
        Instant now = clock.instant();
        Set<Role> configuredRoles = configuredRoles(command.mobile());

        Optional<User> existing = users.findByMobile(command.mobile());
        User user;
        boolean newUser = existing.isEmpty();
        if (newUser) {
            user = users.save(User.register(command.mobile(), configuredRoles, now));
            events.userRegistered(user, command.referralCode());
        } else {
            user = existing.get();
            user.ensureActive();
            if (user.syncRoles(configuredRoles)) {
                user = users.save(user);
            }
        }

        Optional<Device> knownDevice = devices.find(user.id(), command.deviceId());
        Device device;
        if (knownDevice.isPresent()) {
            device = knownDevice.get();
            device.seen(command.deviceName(), now);
        } else {
            device = Device.bind(user.id(), command.deviceId(), command.deviceName(), now);
            if (!newUser) {
                events.newDeviceLogin(user, device, command.ipAddress());
            }
        }
        devices.save(device);

        AuthTokens tokens = tokenService.issueFor(user, command.deviceId());
        return new LoginResult(tokens, newUser, pins.exists(user.id()));
    }

    private Set<Role> configuredRoles(MobileNumber mobile) {
        EnumSet<Role> roles = EnumSet.of(Role.USER);
        if (properties.adminMobiles().contains(mobile.value())) {
            roles.add(Role.ADMIN);
        }
        if (properties.supportMobiles().contains(mobile.value())) {
            roles.add(Role.SUPPORT);
        }
        return roles;
    }
}
