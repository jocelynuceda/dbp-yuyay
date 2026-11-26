package com.yuyay.consultation.dto;

import java.time.Instant;
import java.util.List;

/** A resumed reading never exposes the HttpOnly credential back to JavaScript. */
public record HandoffResumedViewDTO(
        Long handoffId,
        String careSubjectName,
        String reason,
        String questions,
        Instant createdAt,
        List<HandoffItemResponseDTO> items,
        Instant accessExpiresAt,
        Instant serverTime,
        boolean downloadAllowed
) {}
