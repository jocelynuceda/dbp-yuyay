package com.yuyay.consultation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ConsultationDetailDTO(
        Long id,
        Long careSubjectId,
        String careSubjectName,
        Long createdByUserId,
        LocalDate date,
        LocalTime time,
        String reason,
        String notes,
        Instant createdAt
) {}