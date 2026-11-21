package com.yuyay.consultation.repository;

import com.yuyay.consultation.entity.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConsultationRepository extends JpaRepository<Consultation, Long> {
    List<Consultation> findByCareSubjectIdOrderByDateDesc(Long careSubjectId);

    Optional<Consultation> findFirstByCareSubjectIdOrderByDateDesc(Long careSubjectId);

    Optional<Consultation> findByIdAndCareSubjectId(Long id, Long careSubjectId);

    /** Citas de los días que pueden tener un recordatorio pendiente, con su persona cargada. */
    @Query("select c from Consultation c join fetch c.careSubject where c.date between :from and :to")
    List<Consultation> findWithSubjectByDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
