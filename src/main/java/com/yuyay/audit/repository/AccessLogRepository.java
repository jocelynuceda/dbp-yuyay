package com.yuyay.audit.repository;

import com.yuyay.audit.entity.AccessLog;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    @EntityGraph(attributePaths = {"user", "delegation"})
    Page<AccessLog> findByCareSubjectId(Long careSubjectId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "delegation"})
    List<AccessLog> findByCareSubjectIdOrderByOccurredAtDesc(Long careSubjectId);
}
