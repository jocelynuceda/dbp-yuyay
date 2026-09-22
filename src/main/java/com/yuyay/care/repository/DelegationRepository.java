package com.yuyay.care.repository;

import com.yuyay.care.entity.Delegation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {
    @EntityGraph(attributePaths = {"categories", "careSubject"})
    Optional<Delegation> findByTokenHash(String tokenHash);

    @EntityGraph(attributePaths = {"categories"})
    Optional<Delegation> findWithCategoriesById(Long id);

    @EntityGraph(attributePaths = {"categories", "grantedBy"})
    List<Delegation> findByCareSubjectIdOrderByCreatedAtDesc(Long careSubjectId);

    @Query("select count(d) from Delegation d where d.revokedAt is null and d.validUntil > :now")
    long countActive(Instant now);
}
