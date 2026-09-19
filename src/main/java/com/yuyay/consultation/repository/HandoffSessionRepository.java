package com.yuyay.consultation.repository;

import com.yuyay.consultation.entity.HandoffSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HandoffSessionRepository extends JpaRepository<HandoffSession, Long> {
    @EntityGraph(attributePaths = {"handoff", "handoff.careSubject"})
    Optional<HandoffSession> findByTokenHash(String tokenHash);

    List<HandoffSession> findByHandoffIdOrderByCreatedAtDesc(Long handoffId);
}
