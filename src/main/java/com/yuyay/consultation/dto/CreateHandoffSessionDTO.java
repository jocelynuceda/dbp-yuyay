package com.yuyay.consultation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateHandoffSessionDTO(

        @NotNull
        @Min(1)
        @Max(72)
        Integer expiresInHours,

        @NotNull
        @Min(1)
        Integer sessionWindowMinutes

) {}