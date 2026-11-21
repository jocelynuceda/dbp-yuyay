package com.yuyay.consultation.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Un aviso pendiente para un cuidador activo, con lo necesario para escribirlo. */
record DueReminder(
        Long consultationId,
        Long careSubjectId,
        String careSubjectName,
        LocalDate date,
        LocalTime time,
        String reason,
        Long userId,
        String email,
        Instant remindAt
) {}
