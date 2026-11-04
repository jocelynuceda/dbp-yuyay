package com.yuyay.consultation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ConsultationResponseDTO(
        Long id,
        Long careSubjectId,
        Long createdByUserId,
        LocalDate date,
        LocalTime time,
        String reason,
        String notes,
        Instant createdAt
) {}