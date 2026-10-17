package com.payverse.notification.entity;

/** PENDING (to send / retrying), SENT, DEAD (gave up after max attempts). */
public enum NotificationStatus {
    PENDING, SENT, DEAD
}
