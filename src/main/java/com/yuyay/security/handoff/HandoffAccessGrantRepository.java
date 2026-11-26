package com.yuyay.security.handoff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface HandoffAccessGrantRepository extends JpaRepository<HandoffAccessGrant, Long> {
    boolean existsBySessionIdAndTokenHashAndExpiresAtAfter(Long sessionId, String tokenHash, Instant now);
    Optional<HandoffAccessGrant> findBySessionIdAndTokenHashAndExpiresAtAfter(Long sessionId, String tokenHash,
                                                                           Instant now);

    long countBySessionId(Long sessionId);
}
