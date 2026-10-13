package com.payverse.auth.infrastructure.persistence;

import com.payverse.auth.application.port.DeviceRepository;
import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.DeviceId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaDeviceRepository implements DeviceRepository {

    private final DeviceJpaRepository jpa;

    JpaDeviceRepository(DeviceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Device> find(UUID userId, DeviceId deviceId) {
        return jpa.findByUserIdAndDeviceId(userId, deviceId.value()).map(JpaDeviceRepository::toDomain);
    }

    @Override
    public List<Device> findByUser(UUID userId) {
        return jpa.findByUserId(userId).stream().map(JpaDeviceRepository::toDomain).toList();
    }

    @Override
    public Device save(Device device) {
        DeviceEntity e = jpa.findById(device.id()).orElseGet(DeviceEntity::new);
        e.id = device.id();
        e.userId = device.userId();
        e.deviceId = device.deviceId().value();
        e.name = device.name();
        e.boundAt = device.boundAt();
        e.lastSeenAt = device.lastSeenAt();
        e.revokedAt = device.revokedAt();
        return toDomain(jpa.save(e));
    }

    private static Device toDomain(DeviceEntity e) {
        return Device.restore(e.id, e.userId, new DeviceId(e.deviceId), e.name, e.boundAt, e.lastSeenAt, e.revokedAt);
    }
}
