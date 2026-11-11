package com.yuyay.attachment.dto;

public record DocumentScopeSummaryResponse(
        boolean enabled,
        long appropriate,
        long unsure,
        long strikes
) {}
