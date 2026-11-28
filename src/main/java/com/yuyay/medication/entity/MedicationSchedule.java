package com.yuyay.medication.entity;

import com.yuyay.care.entity.CareSubject;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Horario de un medicamento (un registro de categoría MEDICATION): a qué horas se toma, qué días y
 * entre qué fechas. La dosis sigue siendo la del registro; aquí solo se guarda cuándo.
 */
@Entity
@Table(name = "medication_schedules", indexes = @Index(name = "idx_med_schedule_subject", columnList = "care_subject_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "health_entry_id", nullable = false, unique = true)
    private HealthEntry healthEntry;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "care_subject_id", nullable = false)
    private CareSubject careSubject;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "medication_schedule_times", joinColumns = @JoinColumn(name = "schedule_id"))
    @Column(name = "dose_time", nullable = false)
    @OrderBy
    @Builder.Default
    private List<LocalTime> times = new ArrayList<>();

    /** Vacío: todos los días. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "medication_schedule_days", joinColumns = @JoinColumn(name = "schedule_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    @Builder.Default
    private Set<DayOfWeek> daysOfWeek = new HashSet<>();

    @Column(nullable = false)
    private LocalDate startDate;

    /** Sin fecha de fin: tratamiento continuo. */
    private LocalDate endDate;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", nullable = false)
    private User updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Desde cuándo existe el horario. Las tomas anteriores no se muestran ni se marcan: no había
     * horario que seguir. No cambia al editarlo, para que las tomas que ya existían no desaparezcan.
     */
    @Column(updatable = false)
    private Instant createdAt;

    /** ¿Ya existía el horario a esa hora? Al minuto, para no perder la toma de la hora en que se creó. */
    public boolean existedAt(Instant doseAt) {
        Instant since = createdAt != null ? createdAt : updatedAt;
        return !doseAt.isBefore(since.truncatedTo(ChronoUnit.MINUTES));
    }

    /** ¿Toca alguna toma ese día? */
    public boolean appliesOn(LocalDate date) {
        if (date.isBefore(startDate) || (endDate != null && date.isAfter(endDate))) return false;
        return daysOfWeek.isEmpty() || daysOfWeek.contains(date.getDayOfWeek());
    }
}
