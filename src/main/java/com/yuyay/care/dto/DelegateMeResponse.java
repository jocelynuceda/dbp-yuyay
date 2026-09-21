package com.yuyay.care.dto;

import java.time.Instant;
import java.util.Set;

public record DelegateMeResponse(
        Long delegationId,
        Long careSubjectId,
        String careSubjectName,
        String granteeName,
        Set<String> categoryCodes,
        Instant validUntil
) {}
