package com.yuyay.consultation.dto;

import com.yuyay.health.entity.ChangeType;
import com.yuyay.health.entity.ConfidenceLevel;

import java.time.Instant;
import java.time.LocalDate;

public record HandoffItemResponseDTO(
        Long healthEntryId,
        Long healthEntryVersionId,
        String categoryCode,
        Integer versionNumber,
        ChangeType changeType,
        String title,
        String details,
        String dose,
        String frequency,
        LocalDate occurredOn,
        ConfidenceLevel confidenceLevel,
        String notes,
        Instant declaredAt
) {}