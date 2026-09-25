package com.yuyay.attachment.dto;

import com.yuyay.attachment.entity.OcrStatus;

import java.time.Instant;

public record AttachmentResponse(
        Long id,
        Long careSubjectId,
        Long consultationId,
        Long uploadedById,
        String uploadedByName,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        OcrStatus ocrStatus,
        String ocrText,
        String ocrError,
        Instant uploadedAt,
        Instant ocrCompletedAt
) {}
