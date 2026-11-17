package com.yuyay.user.dto;

import com.yuyay.common.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserDTO(@NotBlank @Size(max = 120) String name, Gender gender) {}
