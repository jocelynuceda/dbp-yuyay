package com.yuyay.audit.dto;

import com.yuyay.audit.entity.AccessAction;

import java.time.Instant;

public record AccessLogResponseDTO(
        Long id,
        Long careSubjectId,
        Long userId,
        Long delegationId,
        AccessAction action,
        String targetType,
        Long targetId,
        String visitorName,
        String visitorRole,
        Instant occurredAt
) {}