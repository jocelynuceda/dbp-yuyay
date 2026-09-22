package com.yuyay.care.dto;

import java.time.Instant;
import java.util.Set;

public record DelegationResponse(
        Long id,
        Long careSubjectId,
        String careSubjectName,
        CareUserSummary grantedBy,
        String granteeName,
        String granteeEmail,
        Set<String> categoryCodes,
        Instant validUntil,
        Instant exchangedAt,
        Instant revokedAt,
        Instant createdAt,
        boolean active
) {}
