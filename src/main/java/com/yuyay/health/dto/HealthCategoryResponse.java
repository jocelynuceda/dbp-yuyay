package com.yuyay.health.dto;

public record HealthCategoryResponse(
        Long id,
        String code,
        String name,
        String description
) {}