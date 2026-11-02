package com.yuyay.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateConsultationDTO(

        @NotNull
        LocalDate date,

        @NotBlank
        @Size(max = 200)
        String reason,

        String notes

) {}