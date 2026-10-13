package com.payverse.auth.application;

import com.payverse.auth.application.port.AccessTokenIssuer;
import com.payverse.auth.application.port.AuthEventPublisher;
import com.payverse.auth.application.port.DeviceRepository;
import com.payverse.auth.application.port.OtpSender;
import com.payverse.auth.application.port.OtpStore;
import com.payverse.auth.application.port.RefreshTokenRepository;
import com.payverse.auth.application.port.RequestRateLimiter;
import com.payverse.auth.application.port.TransactionPinRepository;
import com.payverse.auth.application.port.UserRepository;
import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.MobileNumber;
import com.payverse.auth.domain.OtpChallenge;
import com.payverse.auth.domain.OtpPurpose;
import com.payverse.auth.domain.RefreshToken;
import com.payverse.auth.domain.TransactionPin;
import com.payverse.auth.domain.User;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Simple in-memory port implementations for application-layer unit tests. */
final class InMemoryAdapters {

    private InMemoryAdapters() {
    }

    static final class Users implements UserRepository {
        final Map<UUID, User> byId = new HashMap<>();

        @Override
        public Optional<User> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<User> findByMobile(MobileNumber mobile) {
            return byId.values().stream().filter(u -> u.mobile().equals(mobile)).findFirst();
        }

        @Override
        public User save(User user) {
            byId.put(user.id(), user);
            return user;
        }
    }

    static final class Devices implements DeviceRepository {
        final List<Device> devices = new ArrayList<>();

        @Override
        public Optional<Device> find(UUID userId, DeviceId deviceId) {
            return devices.stream().filter(d -> d.userId().equals(userId) && d.deviceId().equals(deviceId)).findFirst();
        }

        @Override
        public List<Device> findByUser(UUID userId) {
            return devices.stream().filter(d -> d.userId().equals(userId)).toList();
        }

        @Override
        public Device save(Device device) {
            devices.removeIf(d -> d.id().equals(device.id()));
            devices.add(device);
            return device;
        }
    }

    static final class RefreshTokens implements RefreshTokenRepository {
        final Map<UUID, RefreshToken> byId = new HashMap<>();

        @Override
        public Optional<RefreshToken> findByHash(String tokenHash) {
            return byId.values().stream().filter(t -> t.tokenHash().equals(tokenHash)).findFirst();
        }

        @Override
        public RefreshToken save(RefreshToken token) {
            byId.put(token.id(), token);
            return token;
        }

        @Override
        public int revokeFamily(UUID familyId, RefreshToken.RevokeReason reason, Instant now) {
            int n = 0;
            for (RefreshToken t : byId.values()) {
                if (t.familyId().equals(familyId) && t.revokedAt() == null) {
                    t.revoke(reason, now);
                    n++;
                }
            }
            return n;
        }

        @Override
        public int revokeAllForDevice(UUID userId, DeviceId deviceId, RefreshToken.RevokeReason reason, Instant now) {
            int n = 0;
            for (RefreshToken t : byId.values()) {
                if (t.userId().equals(userId) && t.deviceId().equals(deviceId) && t.revokedAt() == null) {
                    t.revoke(reason, now);
                    n++;
                }
            }
            return n;
        }
    }

    static final class Pins implements TransactionPinRepository {
        final Map<UUID, TransactionPin> byUser = new HashMap<>();

        @Override
        public Optional<TransactionPin> find(UUID userId) {
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public boolean exists(UUID userId) {
            return byUser.containsKey(userId);
        }

        @Override
        public TransactionPin save(TransactionPin pin) {
            byUser.put(pin.userId(), pin);
            return pin;
        }
    }

    static final class Otps implements OtpStore {
        final Map<String, OtpChallenge> store = new HashMap<>();

        @Override
        public void save(MobileNumber mobile, OtpChallenge challenge) {
            store.put(mobile.value() + challenge.purpose(), challenge);
        }

        @Override
        public Optional<OtpChallenge> find(MobileNumber mobile, OtpPurpose purpose) {
            return Optional.ofNullable(store.get(mobile.value() + purpose));
        }

        @Override
        public void delete(MobileNumber mobile, OtpPurpose purpose) {
            store.remove(mobile.value() + purpose);
        }
    }

    static final class CapturingSender implements OtpSender {
        final Map<String, String> lastCode = new HashMap<>();

        @Override
        public void send(MobileNumber mobile, String code, OtpPurpose purpose) {
            lastCode.put(mobile.value() + purpose, code);
        }

        String code(String mobile, OtpPurpose purpose) {
            return lastCode.get(mobile + purpose);
        }
    }

    static final class CountingLimiter implements RequestRateLimiter {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String key, int limit, Duration window) {
            return counts.merge(key, 1, Integer::sum) <= limit;
        }
    }

    static final class FakeIssuer implements AccessTokenIssuer {
        @Override
        public IssuedToken issue(String subject, Set<String> roles, String deviceId, Duration ttl) {
            return new IssuedToken("jwt-for-" + subject + "-" + roles.size(), Instant.parse("2026-10-14T10:15:00Z"));
        }
    }

    static final class RecordingEvents implements AuthEventPublisher {
        final List<String> events = new ArrayList<>();

        @Override
        public void userRegistered(User user, String referralCode) {
            events.add("registered:" + user.id());
        }

        @Override
        public void newDeviceLogin(User user, Device device, String ipAddress) {
            events.add("new-device:" + device.deviceId().value());
        }

        @Override
        public void pinLocked(UUID userId, Instant lockedUntil) {
            events.add("pin-locked:" + userId);
        }
    }
}
