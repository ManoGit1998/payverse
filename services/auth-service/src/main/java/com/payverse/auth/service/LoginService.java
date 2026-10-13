package com.payverse.auth.service;

import com.payverse.auth.config.AuthProperties;
import com.payverse.auth.dto.AuthTokens;
import com.payverse.auth.dto.LoginResult;
import com.payverse.auth.entity.Device;
import com.payverse.auth.entity.Role;
import com.payverse.auth.entity.User;
import com.payverse.auth.model.DeviceId;
import com.payverse.auth.model.MobileNumber;
import com.payverse.auth.model.OtpPurpose;
import com.payverse.auth.repository.DeviceRepository;
import com.payverse.auth.repository.TransactionPinRepository;
import com.payverse.auth.repository.UserRepository;
import com.payverse.common.web.crypto.BlindIndexer;
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
    private final BlindIndexer blindIndexer;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginService(OtpService otpService, UserRepository users, DeviceRepository devices,
                        TransactionPinRepository pins, TokenService tokenService, AuthEventPublisher events,
                        BlindIndexer blindIndexer, AuthProperties properties, Clock clock) {
        this.otpService = otpService;
        this.users = users;
        this.devices = devices;
        this.pins = pins;
        this.tokenService = tokenService;
        this.events = events;
        this.blindIndexer = blindIndexer;
        this.properties = properties;
        this.clock = clock;
    }

    public record LoginCommand(MobileNumber mobile, String otp, DeviceId deviceId, String deviceName,
                               String referralCode, String ipAddress) {
    }

    @Transactional
    public LoginResult login(LoginCommand command) {
        otpService.verify(command.mobile(), OtpPurpose.LOGIN, command.otp());
        Instant now = clock.instant();
        Set<Role> configuredRoles = configuredRoles(command.mobile());

        Optional<User> existing = users.findByMobileHash(blindIndexer.index(command.mobile().value()));
        boolean newUser = existing.isEmpty();
        User user;
        if (newUser) {
            user = users.save(User.register(command.mobile(), blindIndexer.index(command.mobile().value()), configuredRoles, now));
            events.userRegistered(user, command.referralCode());
        } else {
            user = existing.get();
            user.ensureActive();
            user.syncRoles(configuredRoles);
        }

        Optional<Device> known = devices.findByUserIdAndDeviceId(user.getId(), command.deviceId().value());
        Device device;
        if (known.isPresent()) {
            device = known.get();
            device.seen(command.deviceName(), now);
        } else {
            device = devices.save(Device.bind(user.getId(), command.deviceId(), command.deviceName(), now));
            if (!newUser) {
                events.newDeviceLogin(user, device, command.ipAddress());
            }
        }

        AuthTokens tokens = tokenService.issueFor(user, command.deviceId());
        return new LoginResult(tokens, newUser, pins.existsById(user.getId()));
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
