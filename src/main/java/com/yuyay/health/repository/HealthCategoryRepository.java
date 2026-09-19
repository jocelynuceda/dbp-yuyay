package com.yuyay.health.repository;

import com.yuyay.health.entity.HealthCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HealthCategoryRepository extends JpaRepository<HealthCategory, Long> {
    Optional<HealthCategory> findByCode(String code);

    List<HealthCategory> findByCodeIn(Collection<String> codes);
}
