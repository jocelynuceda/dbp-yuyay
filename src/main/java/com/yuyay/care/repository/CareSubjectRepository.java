package com.yuyay.care.repository;

import com.yuyay.care.entity.CareSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CareSubjectRepository extends JpaRepository<CareSubject, Long> {
    @Query("""
            select cs from CareSubject cs
            join cs.careRelationships cr
            where cr.user.id = :userId and cr.status = com.yuyay.care.entity.CareStatus.ACTIVE
            order by cs.name
            """)
    List<CareSubject> findAllAccessibleBy(Long userId);
}
