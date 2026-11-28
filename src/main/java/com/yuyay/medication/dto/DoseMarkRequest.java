package com.yuyay.medication.dto;

import com.yuyay.medication.entity.DoseStatus;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record DoseMarkRequest(
        @NotNull LocalDate date,
        @NotNull LocalTime time,
        @NotNull DoseStatus status
) {}
