package com.yuyay.consultation.dto;

import java.time.Instant;

public record HandoffSessionResponseDTO(
        Long id,
        Long handoffId,
        Instant expiresAt,
        Integer sessionWindowMinutes,
        Instant firstOpenedAt,
        Instant revokedAt,
        Instant createdAt
) {}