package com.payverse.notification.dto;

import java.util.List;

public record InboxResponse(List<NotificationResponse> items, long unread, int page, int size, long totalElements) {
}
