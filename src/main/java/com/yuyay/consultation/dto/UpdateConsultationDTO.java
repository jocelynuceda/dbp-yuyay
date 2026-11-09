package com.yuyay.consultation.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record UpdateConsultationDTO(

        LocalDate date,

        LocalTime time,

        // Un campo ausente en PATCH significa "no cambiar", así que quitar la hora se pide explícitamente
        Boolean clearTime,

        @Size(max = 200)
        String reason,

        String notes

) {}