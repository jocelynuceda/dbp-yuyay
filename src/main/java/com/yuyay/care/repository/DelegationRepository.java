package com.yuyay.care.repository;

import com.yuyay.care.entity.Delegation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delegation d where d.tokenHash = :tokenHash")
    Optional<Delegation> findForUpdateByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delegation d where d.id = :id")
    Optional<Delegation> findForUpdateById(Long id);

    @EntityGraph(attributePaths = {"categories"})
    Optional<Delegation> findWithCategoriesById(Long id);

    @EntityGraph(attributePaths = {"categories", "grantedBy"})
    List<Delegation> findByCareSubjectIdOrderByCreatedAtDesc(Long careSubjectId);

    @Query("select count(d) from Delegation d where d.revokedAt is null and d.validUntil > :now")
    long countActive(Instant now);
}
