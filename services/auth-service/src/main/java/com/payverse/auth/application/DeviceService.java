package com.payverse.auth.application;

import com.payverse.auth.application.port.DeviceRepository;
import com.payverse.auth.application.port.RefreshTokenRepository;
import com.payverse.auth.domain.AuthErrorCode;
import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.DeviceId;
import com.payverse.auth.domain.RefreshToken;
import com.payverse.common.error.PayverseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Lists and revokes bound devices. Revoking a device ends all its sessions. */
@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, RefreshTokenRepository refreshTokens, Clock clock) {
        this.devices = devices;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Device> activeDevices(UUID userId) {
        return devices.findByUser(userId).stream()
                .filter(Device::isActive)
                .sorted(Comparator.comparing(Device::lastSeenAt).reversed())
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, DeviceId deviceId) {
        Device device = devices.find(userId, deviceId)
                .filter(Device::isActive)
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_DEVICE_NOT_FOUND, null));
        Instant now = clock.instant();
        device.revoke(now);
        devices.save(device);
        refreshTokens.revokeAllForDevice(userId, deviceId, RefreshToken.RevokeReason.DEVICE_REVOKED, now);
    }
}
