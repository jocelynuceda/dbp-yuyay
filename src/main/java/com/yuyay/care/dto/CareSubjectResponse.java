package com.yuyay.care.dto;

import com.yuyay.care.entity.CareRole;
import com.yuyay.common.Gender;

import java.time.Instant;
import java.time.LocalDate;

public record CareSubjectResponse(
        Long id,
        String name,
        LocalDate birthDate,
        Gender gender,
        String notes,
        Instant createdAt,
        CareRole myRole,
        boolean ownProfile
) {}