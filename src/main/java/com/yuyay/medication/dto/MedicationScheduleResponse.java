package com.yuyay.medication.dto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record MedicationScheduleResponse(
        Long id,
        Long healthEntryId,
        Long careSubjectId,
        List<LocalTime> times,
        List<DayOfWeek> daysOfWeek,
        LocalDate startDate,
        LocalDate endDate,
        Instant updatedAt
) {}
