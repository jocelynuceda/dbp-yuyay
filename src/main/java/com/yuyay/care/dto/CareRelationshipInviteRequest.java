package com.yuyay.care.dto;

import com.yuyay.care.entity.CareRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CareRelationshipInviteRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotNull CareRole role,
        @Size(max = 60) String relationshipLabel
) {}