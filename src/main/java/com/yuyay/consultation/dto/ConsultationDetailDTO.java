package com.yuyay.consultation.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ConsultationDetailDTO(
        Long id,
        Long careSubjectId,
        String careSubjectName,
        Long createdByUserId,
        LocalDate date,
        String reason,
        String notes,
        Instant createdAt
) {}