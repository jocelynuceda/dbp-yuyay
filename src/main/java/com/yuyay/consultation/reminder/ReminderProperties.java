package com.yuyay.consultation.reminder;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Regla de los recordatorios de citas. Las citas guardan fecha y hora locales sin zona: se interpretan
 * en {@code zone} (por defecto, hora de Perú).
 */
@ConfigurationProperties(prefix = "app.reminders")
public record ReminderProperties(
        Boolean enabled,
        ZoneId zone,
        LocalTime sendAt,
        Integer maxEmailAttempts,
        Duration interval
) {
    public ReminderProperties {
        if (enabled == null) enabled = true;
        if (zone == null) zone = ZoneId.of("America/Lima");
        if (sendAt == null) sendAt = LocalTime.of(18, 0);
        if (maxEmailAttempts == null || maxEmailAttempts < 1) maxEmailAttempts = 3;
        if (interval == null) interval = Duration.ofMinutes(5);
    }
}
