package com.yuyay.consultation.reminder;

import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lecturas y escrituras de los recordatorios. Cada escritura va en su propia transacción para que un
 * fallo con un destinatario no afecte a los demás; la restricción única evita duplicados.
 */
@Component
@RequiredArgsConstructor
class ReminderDelivery {
    private final ConsultationRepository consultations;
    private final CareRelationshipRepository relationships;
    private final AppointmentReminderRepository reminders;
    private final NotificationRepository notifications;
    private final UserRepository users;

    /**
     * Avisos que corresponde enviar ahora. Los destinatarios se calculan en este momento: un cuidador
     * que perdió el acceso ya no recibe el aviso.
     */
    @Transactional(readOnly = true)
    public List<DueReminder> findDue(ReminderSchedule schedule, Instant now) {
        LocalDate today = schedule.firstCandidateDay(now);
        List<DueReminder> due = new ArrayList<>();
        for (Consultation c : consultations.findWithSubjectByDateBetween(today, today.plusDays(1))) {
            if (!schedule.isDue(c.getDate(), c.getTime(), now)) continue;
            Instant remindAt = schedule.remindAt(c.getDate());
            relationships.findByCareSubjectIdAndStatus(c.getCareSubject().getId(), CareStatus.ACTIVE).forEach(rel ->
                    due.add(new DueReminder(c.getId(), c.getCareSubject().getId(), c.getCareSubject().getName(),
                            c.getDate(), c.getTime(), c.getReason(), rel.getUser().getId(), rel.getUser().getEmail(),
                            remindAt)));
        }
        return due;
    }

    /**
     * Vuelve a comprobar, dentro de la transacción que escribe, que el destinatario sigue activo y que
     * la cita no cambió desde que se armó la lista. Entre ambos momentos alguien pudo revocar el acceso,
     * borrar la cita o cambiarle la fecha o la hora.
     */
    private boolean stillValid(DueReminder due) {
        if (!relationships.existsByUserIdAndCareSubjectIdAndStatus(due.userId(), due.careSubjectId(), CareStatus.ACTIVE)) {
            return false;
        }
        return consultations.findById(due.consultationId())
                .filter(c -> c.getCareSubject().getId().equals(due.careSubjectId()))
                .filter(c -> c.getDate().equals(due.date()) && Objects.equals(c.getTime(), due.time()))
                .isPresent();
    }

    /** Guarda el aviso en la app y su control en la misma transacción. false si ya existía o ya no corresponde. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliverInApp(DueReminder due, Instant now, String title, String body) {
        if (!stillValid(due)) return false;
        if (reminders.findByConsultationIdAndUserIdAndChannelAndRemindAt(
                due.consultationId(), due.userId(), ReminderChannel.IN_APP, due.remindAt()).isPresent()) {
            return false;
        }
        reminders.saveAndFlush(AppointmentReminder.builder()
                .consultation(consultations.getReferenceById(due.consultationId()))
                .user(users.getReferenceById(due.userId()))
                .channel(ReminderChannel.IN_APP).remindAt(due.remindAt())
                .status(ReminderStatus.SENT).attempts(1).sentAt(now).updatedAt(now)
                .build());
        notifications.save(Notification.builder()
                .user(users.getReferenceById(due.userId()))
                .type(NotificationType.APPOINTMENT_REMINDER)
                .careSubjectId(due.careSubjectId())
                .title(title)
                .body(body)
                .build());
        return true;
    }

    /**
     * Reserva un intento de correo antes de enviarlo. Devuelve el id del control, o vacío si ya se
     * envió, está en curso, agotó sus intentos o el destinatario ya no debe recibirlo. Si el servidor cae justo al enviar, el control queda
     * en PENDING y no se reenvía: es preferible a mandar el mismo correo dos veces.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> claimEmail(DueReminder due, Instant now, int maxAttempts) {
        if (!stillValid(due)) return Optional.empty();
        Optional<AppointmentReminder> existing = reminders.findByConsultationIdAndUserIdAndChannelAndRemindAt(
                due.consultationId(), due.userId(), ReminderChannel.EMAIL, due.remindAt());
        if (existing.isEmpty()) {
            AppointmentReminder created = reminders.saveAndFlush(AppointmentReminder.builder()
                    .consultation(consultations.getReferenceById(due.consultationId()))
                    .user(users.getReferenceById(due.userId()))
                    .channel(ReminderChannel.EMAIL).remindAt(due.remindAt())
                    .status(ReminderStatus.PENDING).attempts(1).updatedAt(now)
                    .build());
            return Optional.of(created.getId());
        }
        AppointmentReminder reminder = existing.get();
        if (reminder.getStatus() != ReminderStatus.FAILED || reminder.getAttempts() >= maxAttempts) {
            return Optional.empty();
        }
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setAttempts(reminder.getAttempts() + 1);
        reminder.setUpdatedAt(now);
        return Optional.of(reminder.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishEmail(Long reminderId, boolean sent, Instant now) {
        reminders.findById(reminderId).ifPresent(reminder -> {
            reminder.setStatus(sent ? ReminderStatus.SENT : ReminderStatus.FAILED);
            reminder.setSentAt(sent ? now : null);
            reminder.setUpdatedAt(now);
        });
    }
}
