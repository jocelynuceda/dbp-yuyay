package com.yuyay.health.repository;

import com.yuyay.health.entity.HealthEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HealthEntryRepository extends JpaRepository<HealthEntry, Long> {
    @EntityGraph(attributePaths = {"category", "createdBy"})
    List<HealthEntry> findByCareSubjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long careSubjectId);

    @EntityGraph(attributePaths = {"category", "createdBy"})
    List<HealthEntry> findByCareSubjectIdAndCategoryCodeAndDeletedAtIsNullOrderByCreatedAtDesc(Long careSubjectId, String categoryCode);

    @Query("""
            select e from HealthEntry e
            join fetch e.category c
            where e.careSubject.id = :careSubjectId
              and e.deletedAt is null
              and c.code in :categoryCodes
            order by e.createdAt desc
            """)
    List<HealthEntry> findAccessibleByDelegation(Long careSubjectId, Collection<String> categoryCodes);

    @EntityGraph(attributePaths = {"category", "careSubject"})
    Optional<HealthEntry> findWithCategoryById(Long id);

    List<HealthEntry> findByIdInAndCareSubjectId(Collection<Long> ids, Long careSubjectId);

    boolean existsByCareSubjectId(Long careSubjectId);
}
