package com.yuyay.consultation.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface AppointmentReminderRepository extends JpaRepository<AppointmentReminder, Long> {
    Optional<AppointmentReminder> findByConsultationIdAndUserIdAndChannelAndRemindAt(
            Long consultationId, Long userId, ReminderChannel channel, Instant remindAt);
}
