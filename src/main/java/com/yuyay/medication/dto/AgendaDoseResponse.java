package com.yuyay.medication.dto;

import com.yuyay.medication.entity.DoseStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Una toma de la agenda. {@code status} es null si nadie la marcó todavía. {@code mine}: la toma es de
 * quien consulta (su perfil propio), no de alguien a quien cuida.
 */
public record AgendaDoseResponse(
        Long scheduleId,
        Long healthEntryId,
        Long careSubjectId,
        String careSubjectName,
        boolean mine,
        String medication,
        String dose,
        LocalDate date,
        LocalTime time,
        DoseStatus status,
        String markedByName,
        Instant markedAt
) {}
