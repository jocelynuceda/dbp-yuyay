package com.yuyay.health.repository;

import com.yuyay.health.entity.HealthEntryVersion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HealthEntryVersionRepository extends JpaRepository<HealthEntryVersion, Long> {
    @EntityGraph(attributePaths = {"declaredBy"})
    List<HealthEntryVersion> findByHealthEntryIdOrderByVersionNumberDesc(Long healthEntryId);

    @EntityGraph(attributePaths = {"declaredBy"})
    Optional<HealthEntryVersion> findFirstByHealthEntryIdOrderByVersionNumberDesc(Long healthEntryId);

    @Query("""
            select v from HealthEntryVersion v
            join fetch v.healthEntry e
            join fetch e.category
            join fetch v.declaredBy
            where e.careSubject.id = :careSubjectId
              and v.declaredAt > :since
            order by v.declaredAt desc
            """)
    List<HealthEntryVersion> findChangesSince(Long careSubjectId, Instant since);
}
