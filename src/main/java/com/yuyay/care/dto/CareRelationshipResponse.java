package com.yuyay.care.dto;

import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;

import java.time.Instant;

public record CareRelationshipResponse(
        Long id,
        Long careSubjectId,
        String careSubjectName,
        CareUserSummary user,
        CareRole role,
        CareStatus status,
        String relationshipLabel,
        Instant invitedAt,
        Instant acceptedAt,
        Instant revokedAt
) {}