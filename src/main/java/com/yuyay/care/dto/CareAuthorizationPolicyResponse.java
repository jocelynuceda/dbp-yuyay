package com.yuyay.care.dto;

import com.yuyay.care.entity.CareAuthorizationScope;
import com.yuyay.care.entity.CareAuthorizationType;

import java.util.List;

public record CareAuthorizationPolicyResponse(
        String textVersion,
        String thirdPartyQuestion,
        List<Option> options
) {
    public record Option(
            CareAuthorizationType type,
            String label,
            String description,
            CareAuthorizationScope scope,
            String confirmationText
    ) {}
}
