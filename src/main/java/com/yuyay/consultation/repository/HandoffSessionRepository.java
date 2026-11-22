package com.yuyay.consultation.repository;

import com.yuyay.consultation.entity.HandoffSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface HandoffSessionRepository extends JpaRepository<HandoffSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from HandoffSession s where s.id = :id")
    Optional<HandoffSession> findForUpdateById(Long id);

    @EntityGraph(attributePaths = {"handoff", "handoff.careSubject"})
    Optional<HandoffSession> findByTokenHash(String tokenHash);

    List<HandoffSession> findByHandoffIdOrderByCreatedAtDesc(Long handoffId);
}
