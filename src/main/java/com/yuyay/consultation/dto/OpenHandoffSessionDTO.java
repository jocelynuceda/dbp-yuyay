package com.yuyay.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpenHandoffSessionDTO(

        @NotBlank
        @Size(max = 120)
        String visitorName,

        @NotBlank
        @Size(max = 80)
        String visitorRole

) {}