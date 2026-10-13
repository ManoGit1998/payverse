package com.payverse.auth.application.port;

import com.payverse.auth.domain.Device;
import com.payverse.auth.domain.DeviceId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository {

    Optional<Device> find(UUID userId, DeviceId deviceId);

    List<Device> findByUser(UUID userId);

    Device save(Device device);
}
