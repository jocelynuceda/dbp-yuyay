package com.yuyay.care.repository;

import com.yuyay.care.entity.CareSubject;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CareSubjectRepository extends JpaRepository<CareSubject, Long> {
    Optional<CareSubject> findByAccountUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cs from CareSubject cs where cs.id = :id")
    Optional<CareSubject> findForUpdateById(Long id);

    @Query("""
            select cs from CareSubject cs
            join cs.careRelationships cr
            where cr.user.id = :userId and cr.status = com.yuyay.care.entity.CareStatus.ACTIVE
            order by cs.name
            """)
    List<CareSubject> findAllAccessibleBy(Long userId);
}
