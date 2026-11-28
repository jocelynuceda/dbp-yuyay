package com.yuyay.medication.repository;

import com.yuyay.medication.entity.MedicationSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MedicationScheduleRepository extends JpaRepository<MedicationSchedule, Long> {
    Optional<MedicationSchedule> findByHealthEntryId(Long healthEntryId);

    @Query("""
            select s from MedicationSchedule s join fetch s.healthEntry e join fetch s.careSubject
            where s.careSubject.id = :subjectId and e.deletedAt is null
            """)
    List<MedicationSchedule> findActiveBySubject(Long subjectId);

    /** Horarios de esas personas que tocan en algún día del rango, sin los registros eliminados. */
    @Query("""
            select s from MedicationSchedule s join fetch s.healthEntry e join fetch s.careSubject
            where s.careSubject.id in :subjectIds and e.deletedAt is null
              and s.startDate <= :to and (s.endDate is null or s.endDate >= :from)
            """)
    List<MedicationSchedule> findInRange(Collection<Long> subjectIds, LocalDate from, LocalDate to);
}
