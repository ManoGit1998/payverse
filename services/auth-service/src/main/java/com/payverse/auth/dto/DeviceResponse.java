package com.payverse.auth.dto;

import com.payverse.auth.entity.Device;

import java.time.Instant;

public record DeviceResponse(String deviceId, String name, Instant boundAt, Instant lastSeenAt, boolean current) {

    public static DeviceResponse of(Device device, String currentDeviceId) {
        return new DeviceResponse(device.getDeviceId(), device.getName(), device.getBoundAt(), device.getLastSeenAt(),
                device.getDeviceId().equals(currentDeviceId));
    }
}
