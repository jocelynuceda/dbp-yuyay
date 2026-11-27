package com.yuyay.care.dto;

import java.time.Instant;

public record ClaimInvitationResponse(
        Long id,
        Long careSubjectId,
        String invitedEmail,
        Instant validUntil,
        Instant exchangedAt,
        Instant revokedAt,
        boolean pending
) {}
