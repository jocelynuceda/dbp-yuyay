package com.yuyay.medication.repository;

import com.yuyay.medication.entity.DoseLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DoseLogRepository extends JpaRepository<DoseLog, Long> {
    Optional<DoseLog> findByScheduleIdAndDateAndTime(Long scheduleId, LocalDate date, LocalTime time);

    @Modifying
    @Query("delete from DoseLog l where l.schedule.id = :scheduleId")
    void deleteByScheduleId(Long scheduleId);

    @Query("""
            select l from DoseLog l join fetch l.markedBy
            where l.schedule.id in :scheduleIds and l.date between :from and :to
            """)
    List<DoseLog> findInRange(Collection<Long> scheduleIds, LocalDate from, LocalDate to);
}
