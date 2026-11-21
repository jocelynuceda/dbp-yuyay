package com.yuyay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record GoogleAuthRequestDTO(
        @NotBlank @Size(max = 8192) String idToken,
        @Schema(type = "boolean") JsonNode termsAccepted,
        @Schema(type = "string") JsonNode termsVersion
) {
    @Override
    public String toString() {
        return "GoogleAuthRequestDTO[idToken=***]";
    }
}
