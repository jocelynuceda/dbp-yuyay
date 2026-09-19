package com.yuyay.consultation.repository;

import com.yuyay.consultation.entity.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsultationRepository extends JpaRepository<Consultation, Long> {
    List<Consultation> findByCareSubjectIdOrderByDateDesc(Long careSubjectId);

    Optional<Consultation> findFirstByCareSubjectIdOrderByDateDesc(Long careSubjectId);

    Optional<Consultation> findByIdAndCareSubjectId(Long id, Long careSubjectId);
}
