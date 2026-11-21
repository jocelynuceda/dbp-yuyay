package com.yuyay.auth.dto;

import com.yuyay.auth.validation.PasswordBytes;
import com.yuyay.common.Gender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record RegisterRequestDTO(
        @NotBlank @Email @Size(max = 150) String email,
        @PasswordBytes
        @NotBlank @Size(min = 8, max = 72)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "debe contener al menos una letra y un número")
        String password,
        @NotBlank @Size(max = 120) String name,
        Gender gender,
        @Schema(type = "boolean", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode termsAccepted,
        @Schema(type = "string", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode termsVersion
) {}
