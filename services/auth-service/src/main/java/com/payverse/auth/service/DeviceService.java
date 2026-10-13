package com.payverse.auth.service;

import com.payverse.auth.entity.Device;
import com.payverse.auth.entity.RevokeReason;
import com.payverse.auth.exception.AuthErrorCode;
import com.payverse.auth.model.DeviceId;
import com.payverse.auth.repository.DeviceRepository;
import com.payverse.auth.repository.RefreshTokenRepository;
import com.payverse.common.error.PayverseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
        return devices.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId);
    }

    @Transactional
    public void revoke(UUID userId, DeviceId deviceId) {
        Device device = devices.findByUserIdAndDeviceId(userId, deviceId.value())
                .filter(Device::isActive)
                .orElseThrow(() -> new PayverseException(AuthErrorCode.AUTH_DEVICE_NOT_FOUND, null));
        Instant now = clock.instant();
        device.revoke(now);
        devices.saveAndFlush(device);
        refreshTokens.revokeDevice(userId, deviceId.value(), RevokeReason.DEVICE_REVOKED, now);
    }
}
