package com.yuyay.health.dto;

import java.time.Instant;

public record HealthEntryResponse(
        Long id,
        Long careSubjectId,
        String categoryCode,
        String categoryName,
        UserSummary createdBy,
        Instant createdAt,
        Instant deletedAt,
        HealthEntryVersionResponse latestVersion
) {}