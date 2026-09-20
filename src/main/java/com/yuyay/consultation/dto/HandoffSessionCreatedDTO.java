package com.yuyay.consultation.dto;

import java.time.Instant;

public record HandoffSessionCreatedDTO(
        Long id,
        String token,
        Instant expiresAt,
        Integer sessionWindowMinutes,
        Instant createdAt
) {}