package com.yuyay.care.dto;

public record DelegationCreatedResponse(
        DelegationResponse delegation,
        String token,
        String shareLink
) {}
