package com.yuyay.attachment.ratelimit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface OcrRequestLogRepository extends JpaRepository<OcrRequestLog, Long> {
    long countByUserIdAndRequestedAtGreaterThanEqual(Long userId, Instant since);

    Optional<OcrRequestLog> findFirstByUserIdAndRequestedAtGreaterThanEqualOrderByRequestedAtAsc(Long userId, Instant since);

    @Modifying
    @Query("delete from OcrRequestLog l where l.userId = :userId and l.requestedAt < :before")
    void deleteOlderThan(Long userId, Instant before);
}
