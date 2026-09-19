package com.yuyay.consultation.dto;

import java.time.Instant;

public record HandoffResponseDTO(
        Long id,
        Long careSubjectId,
        Long createdByUserId,
        String reason,
        String questions,
        Instant createdAt
) {}