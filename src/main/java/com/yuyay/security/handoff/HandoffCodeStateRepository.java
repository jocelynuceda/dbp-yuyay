package com.yuyay.security.handoff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface HandoffCodeStateRepository extends JpaRepository<HandoffCodeState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from HandoffCodeState s where s.sessionId = :sessionId")
    Optional<HandoffCodeState> findForUpdateBySessionId(Long sessionId);
}
