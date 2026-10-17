package com.payverse.notification.exception;

import com.payverse.common.error.ErrorCode;

/** Error codes owned by notification-service. */
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_INVALID_TEMPLATE(400, "Invalid template"),
    NOTIFICATION_NOT_FOUND(404, "Notification not found"),
    NOTIFICATION_TEMPLATE_NOT_FOUND(404, "Template not found"),
    NOTIFICATION_DEAD_LETTER_NOT_FOUND(404, "Dead letter not found"),
    NOTIFICATION_ALREADY_REPLAYED(409, "Dead letter was already replayed");

    private final int httpStatus;
    private final String title;

    NotificationErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
