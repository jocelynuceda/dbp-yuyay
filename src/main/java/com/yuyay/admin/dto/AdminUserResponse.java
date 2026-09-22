package com.yuyay.admin.dto;

import com.yuyay.user.entity.Role;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String email,
        String name,
        Role role,
        Instant createdAt,
        long careSubjectsCount
) {}
