package com.yuyay.consultation.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateConsultationDTO(

        LocalDate date,

        @Size(max = 200)
        String reason,

        String notes

) {}