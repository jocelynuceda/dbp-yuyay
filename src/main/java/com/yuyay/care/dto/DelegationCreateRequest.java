package com.yuyay.care.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public record DelegationCreateRequest(
        @NotBlank @Size(max = 120) String granteeName,
        @NotBlank @Email @Size(max = 150) String granteeEmail,
        @NotEmpty Set<@NotBlank String> categoryCodes,
        @NotNull @Future Instant validUntil
) {}
