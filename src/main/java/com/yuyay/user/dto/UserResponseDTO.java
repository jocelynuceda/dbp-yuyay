package com.yuyay.user.dto;

import com.yuyay.common.Gender;
import com.yuyay.user.entity.Role;

import java.time.Instant;

public record UserResponseDTO(Long id, String email, String name, Role role, Gender gender, Instant createdAt, String fullName) {}
