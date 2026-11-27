package com.yuyay.care.dto;

import com.yuyay.care.entity.CareRole;

import java.time.Instant;

public record ClaimExchangeResponse(
        Long careSubjectId,
        String name,
        CareRole role,
        Instant claimedAt
) {}
