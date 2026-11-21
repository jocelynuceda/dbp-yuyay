package com.yuyay.consultation.dto;

import com.yuyay.health.dto.HealthEntryVersionResponse;
import java.util.List;

public record ConsultationPreparationDTO(Long consultationId, Long careSubjectId, String reason,
        String preparationToken, List<Category> categories) {
    public record Category(String categoryCode, List<Entry> entries) {}
    public record Entry(Long entryId, Long versionId, HealthEntryVersionResponse version) {}
}
