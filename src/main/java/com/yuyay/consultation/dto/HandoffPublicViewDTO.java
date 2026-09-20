package com.yuyay.consultation.dto;

import java.time.Instant;
import java.util.List;

public record HandoffPublicViewDTO(
        Long handoffId,
        String careSubjectName,
        String reason,
        String questions,
        Instant createdAt,
        List<HandoffItemResponseDTO> items
) {}