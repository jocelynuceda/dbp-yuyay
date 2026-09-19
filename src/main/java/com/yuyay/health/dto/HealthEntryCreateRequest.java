package com.yuyay.health.dto;

import com.yuyay.health.entity.ConfidenceLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record HealthEntryCreateRequest(
        @NotBlank @Size(max = 40) String categoryCode,
        @NotBlank @Size(max = 150) String title,
        String details,
        @Size(max = 60) String dose,
        @Size(max = 60) String frequency,
        LocalDate occurredOn,
        @NotNull ConfidenceLevel confidenceLevel,
        String notes
) {}