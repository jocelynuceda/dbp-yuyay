package com.yuyay.consultation.reminder;

import com.yuyay.config.AppProperties;
import com.yuyay.notification.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Envía los recordatorios de citas que ya corresponden: un aviso en la app y un correo discreto a
 * cada cuidador activo. Se puede llamar varias veces sin duplicar envíos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentReminderService {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final ReminderDelivery delivery;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final ReminderProperties properties;
    private final Clock clock;

    /** Devuelve cuántos avisos y correos se enviaron en esta pasada. */
    public int sendDue() {
        Instant now = clock.instant();
        ReminderSchedule schedule = new ReminderSchedule(properties.zone(), properties.sendAt());
        LocalDate today = schedule.firstCandidateDay(now);
        int sent = 0;
        for (DueReminder due : delivery.findDue(schedule, now)) {
            String when = whenText(due, today);
            try {
                if (delivery.deliverInApp(due, now, "Cita de %s %s".formatted(due.careSubjectName(), when.split(" ")[0]),
                        "%s: %s".formatted(capitalize(when), due.reason()))) {
                    sent++;
                }
            } catch (DataIntegrityViolationException duplicate) {
                // Otra pasada ya lo registró
            }
            if (sendEmail(due, when, now)) sent++;
        }
        return sent;
    }

    private boolean sendEmail(DueReminder due, String when, Instant now) {
        Optional<Long> claim;
        try {
            claim = delivery.claimEmail(due, now, properties.maxEmailAttempts());
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
        if (claim.isEmpty()) return false;
        try {
            // Texto discreto: sin el motivo de la cita; el enlace exige iniciar sesión en Yuyay
            emailService.sendTemplate(due.email(), "Yuyay · recordatorio de cita", "appointment-reminder", Map.of(
                    "careSubjectName", due.careSubjectName(),
                    "when", when,
                    "link", "%s/people/%d?tab=appointments".formatted(appProperties.frontendUrl(), due.careSubjectId())));
            delivery.finishEmail(claim.get(), true, now);
            return true;
        } catch (Exception e) {
            log.warn("No se pudo enviar el recordatorio de la cita {} (intento fallido)", due.consultationId(), e);
            delivery.finishEmail(claim.get(), false, now);
            return false;
        }
    }

    /** «mañana a las 10:00», «hoy sin hora registrada»… en la zona de las citas. */
    private static String whenText(DueReminder due, LocalDate today) {
        String day = due.date().equals(today) ? "hoy" : "mañana";
        return due.time() != null ? "%s a las %s".formatted(day, HOUR.format(due.time())) : "%s (sin hora registrada)".formatted(day);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
