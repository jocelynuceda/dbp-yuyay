package com.yuyay.consultation.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ConsultationResponseDTO(
        Long id,
        Long careSubjectId,
        Long createdByUserId,
        LocalDate date,
        String reason,
        String notes,
        Instant createdAt
) {}