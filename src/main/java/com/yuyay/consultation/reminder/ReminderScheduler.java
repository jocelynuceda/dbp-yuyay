package com.yuyay.consultation.reminder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Revisa las citas cada {@code app.reminders.interval}. Al arrancar después de estar apagado envía
 * los avisos atrasados de citas que todavía no empiezan. Se desactiva con {@code app.reminders.enabled=false}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
class ReminderScheduler {
    private final AppointmentReminderService service;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.reminders.interval:PT5M}")
    void run() {
        try {
            int sent = service.sendDue();
            if (sent > 0) log.info("Recordatorios de citas enviados: {}", sent);
        } catch (Exception e) {
            log.error("Fallo revisando recordatorios de citas", e);
        }
    }
}
