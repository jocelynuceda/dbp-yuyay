package com.yuyay.audit.repository;

import com.yuyay.audit.entity.AccessLog;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    @EntityGraph(attributePaths = {"user", "delegation"})
    List<AccessLog> findByCareSubjectIdOrderByOccurredAtDesc(Long careSubjectId);
}
