package com.yuyay.consultation.dto;

import java.time.Instant;
import java.util.List;

public record HandoffDetailDTO(
        Long id,
        Long careSubjectId,
        Long createdByUserId,
        String reason,
        String questions,
        Instant createdAt,
        List<HandoffItemResponseDTO> items
) {}