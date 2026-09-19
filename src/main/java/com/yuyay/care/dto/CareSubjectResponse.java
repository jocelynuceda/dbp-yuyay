package com.yuyay.care.dto;

import com.yuyay.care.entity.CareRole;

import java.time.Instant;
import java.time.LocalDate;

public record CareSubjectResponse(
        Long id,
        String name,
        LocalDate birthDate,
        String notes,
        Instant createdAt,
        CareRole myRole
) {}