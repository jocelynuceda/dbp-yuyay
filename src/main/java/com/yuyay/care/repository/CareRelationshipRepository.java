package com.yuyay.care.repository;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CareRelationshipRepository extends JpaRepository<CareRelationship, Long> {
    boolean existsByUserIdAndCareSubjectIdAndStatus(Long userId, Long careSubjectId, CareStatus status);

    boolean existsByUserIdAndCareSubjectIdAndStatusAndRole(Long userId, Long careSubjectId, CareStatus status, CareRole role);

    Optional<CareRelationship> findByUserIdAndCareSubjectId(Long userId, Long careSubjectId);

    @EntityGraph(attributePaths = {"user", "careSubject"})
    List<CareRelationship> findByCareSubjectIdAndStatus(Long careSubjectId, CareStatus status);

    @EntityGraph(attributePaths = {"user", "careSubject"})
    List<CareRelationship> findByCareSubjectId(Long careSubjectId);

    @EntityGraph(attributePaths = {"user", "careSubject"})
    List<CareRelationship> findByUserIdAndStatus(Long userId, CareStatus status);

    long countByCareSubjectIdAndStatusAndRole(Long careSubjectId, CareStatus status, CareRole role);

    long countByStatus(CareStatus status);

    long countByUserIdAndStatus(Long userId, CareStatus status);
}
