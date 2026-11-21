package com.yuyay.care.dto;

import com.yuyay.care.entity.CareAuthorizationScope;
import com.yuyay.care.entity.CareAuthorizationType;

import java.time.Instant;

public record CareAuthorizationDeclarationResponse(
        Long id,
        Long careSubjectId,
        Long declaredByUserId,
        CareAuthorizationType type,
        CareAuthorizationScope scope,
        String textVersion,
        String text,
        Instant declaredAt
) {}
