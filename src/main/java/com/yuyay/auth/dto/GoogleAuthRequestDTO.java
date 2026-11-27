package com.yuyay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record GoogleAuthRequestDTO(
        @NotBlank @Size(max = 8192) String idToken,
        @Schema(type = "string", maxLength = 32) JsonNode acceptedTermsVersion,
        String dni,
        String fullName,
        String name
) {
    @Override
    public String toString() {
        return "GoogleAuthRequestDTO[idToken=***]";
    }
}
