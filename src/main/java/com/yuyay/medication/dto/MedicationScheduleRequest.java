package com.yuyay.medication.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * @param times      horas de cada toma, de 1 a 8 («08:00», «20:00»)
 * @param daysOfWeek vacío u omitido: todos los días
 * @param endDate    omitido: tratamiento continuo
 */
public record MedicationScheduleRequest(
        @NotEmpty(message = "Agrega al menos una hora") @Size(max = 8, message = "Máximo 8 tomas al día") List<@NotNull LocalTime> times,
        Set<@NotNull DayOfWeek> daysOfWeek,
        @NotNull(message = "Indica desde cuándo") LocalDate startDate,
        LocalDate endDate
) {}
