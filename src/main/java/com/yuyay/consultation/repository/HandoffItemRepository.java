package com.yuyay.consultation.repository;

import com.yuyay.consultation.entity.HandoffItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoffItemRepository extends JpaRepository<HandoffItem, Long> {
}
