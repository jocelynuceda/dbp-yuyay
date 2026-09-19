package com.yuyay.user.dto;

import com.yuyay.user.entity.Role;

import java.time.Instant;

public record UserResponseDTO(Long id, String email, String name, Role role, Instant createdAt) {}
