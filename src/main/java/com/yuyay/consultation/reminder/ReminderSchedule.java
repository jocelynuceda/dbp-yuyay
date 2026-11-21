package com.yuyay.consultation.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Regla inicial: un aviso el día anterior a la hora configurada, para citas con y sin hora.
 * Se envía mientras la cita no haya empezado; una cita sin hora se considera vigente todo su día.
 */
public record ReminderSchedule(ZoneId zone, LocalTime sendAt) {

    public Instant remindAt(LocalDate date) {
        return date.minusDays(1).atTime(sendAt).atZone(zone).toInstant();
    }

    public Instant startsAt(LocalDate date, LocalTime time) {
        return time != null
                ? date.atTime(time).atZone(zone).toInstant()
                : date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** Ya llegó la hora del aviso y la cita todavía no empieza (también si el servidor estuvo apagado). */
    public boolean isDue(LocalDate date, LocalTime time, Instant now) {
        return !now.isBefore(remindAt(date)) && now.isBefore(startsAt(date, time));
    }

    /** Días cuyas citas pueden tener un aviso pendiente: hoy y mañana en la zona de las citas. */
    public LocalDate firstCandidateDay(Instant now) {
        return LocalDate.ofInstant(now, zone);
    }
}
