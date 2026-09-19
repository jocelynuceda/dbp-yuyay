package com.yuyay.consultation.repository;

import com.yuyay.consultation.entity.Handoff;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HandoffRepository extends JpaRepository<Handoff, Long> {
    @EntityGraph(attributePaths = {"createdBy"})
    List<Handoff> findByCareSubjectIdOrderByCreatedAtDesc(Long careSubjectId);

    @EntityGraph(attributePaths = {"careSubject", "createdBy", "items", "items.healthEntryVersion", "items.healthEntryVersion.healthEntry", "items.healthEntryVersion.healthEntry.category"})
    Optional<Handoff> findDetailById(Long id);
}
