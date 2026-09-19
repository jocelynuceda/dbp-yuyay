package com.yuyay.health.dto;

import com.yuyay.health.entity.ChangeType;
import com.yuyay.health.entity.ConfidenceLevel;

import java.time.Instant;
import java.time.LocalDate;

public record HealthEntryVersionResponse(
        Long id,
        Integer versionNumber,
        ChangeType changeType,
        String title,
        String details,
        String dose,
        String frequency,
        LocalDate occurredOn,
        ConfidenceLevel confidenceLevel,
        String notes,
        UserSummary declaredBy,
        Instant declaredAt
) {}