package com.yuyay.health.repository;

import com.yuyay.health.entity.HealthEntryVersion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HealthEntryVersionRepository extends JpaRepository<HealthEntryVersion, Long> {
    boolean existsBySourceAttachmentId(Long attachmentId);

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

    // Última versión de cada entrada en una sola consulta (evita N+1 en el listado)
    @Query("""
            select v from HealthEntryVersion v
            join fetch v.declaredBy
            where v.healthEntry.id in :entryIds
              and v.versionNumber = (select max(v2.versionNumber) from HealthEntryVersion v2 where v2.healthEntry.id = v.healthEntry.id)
            """)
    List<HealthEntryVersion> findLatestByHealthEntryIdIn(Collection<Long> entryIds);
}
