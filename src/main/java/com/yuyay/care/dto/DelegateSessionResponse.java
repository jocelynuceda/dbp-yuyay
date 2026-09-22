package com.yuyay.care.dto;

import java.time.Instant;
import java.util.Set;

public record DelegateSessionResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        Long careSubjectId,
        String careSubjectName,
        String granteeName,
        Set<String> categoryCodes
) {}
