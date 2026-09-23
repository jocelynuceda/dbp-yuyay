package com.yuyay.admin.dto;

import com.yuyay.user.entity.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull Role role) {}
