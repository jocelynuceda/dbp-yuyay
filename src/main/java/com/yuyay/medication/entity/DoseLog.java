package com.yuyay.medication.entity;

import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Una toma marcada: quién la marcó, cuándo y si se tomó o no. Una por horario, día y hora. */
@Entity
@Table(name = "dose_logs", uniqueConstraints = @UniqueConstraint(name = "uk_dose_log",
        columnNames = {"schedule_id", "dose_date", "dose_time"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoseLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private MedicationSchedule schedule;

    @Column(name = "dose_date", nullable = false)
    private LocalDate date;

    @Column(name = "dose_time", nullable = false)
    private LocalTime time;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DoseStatus status;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "marked_by", nullable = false)
    private User markedBy;

    @Column(nullable = false)
    private Instant markedAt;
}
