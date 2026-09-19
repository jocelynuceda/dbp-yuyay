package com.yuyay.notification.dto;

import com.yuyay.notification.entity.NotificationType;

import java.time.Instant;

public record NotificationResponseDTO(
        Long id,
        NotificationType type,
        String title,
        String body,
        Long careSubjectId,
        boolean read,
        Instant readAt,
        Instant createdAt
) {}