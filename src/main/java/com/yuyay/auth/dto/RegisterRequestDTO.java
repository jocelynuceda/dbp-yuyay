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
        /** Nombre de usuario: el que se muestra en la app. */
        @NotBlank @Size(max = 120) String name,
        Gender gender,
        /** Obligatorio. Solo sirve para consultar el nombre completo; no se guarda ni se escribe en logs. */
        @Pattern(regexp = "\\d{8}", message = "El DNI debe tener ocho dígitos") String dni,
        /** Solo se usa si la consulta de DNI no está configurada; si lo está, manda el directorio. */
        @Size(max = 200) String fullName,
        @Schema(type = "boolean", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode termsAccepted,
        @Schema(type = "string", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode termsVersion
) {
    @Override
    public String toString() {
        return "RegisterRequestDTO[datos de registro reservados]";
    }
}
