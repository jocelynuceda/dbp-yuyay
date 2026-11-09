package com.yuyay.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateConsultationDTO(

        @NotNull
        LocalDate date,

        LocalTime time,

        @NotBlank
        @Size(max = 200)
        String reason,

        String notes

) {}