package com.yuyay.consultation.reminder;

import com.yuyay.consultation.entity.Consultation;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * Control de envío de un recordatorio: una fila por cita, destinatario, canal y momento previsto.
 * {@code remindAt} cambia si se edita la fecha de la cita, así la cita reprogramada se vuelve a avisar.
 */
@Entity
@Table(
        name = "appointment_reminders",
        uniqueConstraints = @UniqueConstraint(name = "uk_appointment_reminder",
                columnNames = {"consultation_id", "user_id", "channel", "remind_at"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentReminder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_appointment_reminder_consultation"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Consultation consultation;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_appointment_reminder_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReminderChannel channel;

    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReminderStatus status;

    @Column(nullable = false)
    private int attempts;

    private Instant sentAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
