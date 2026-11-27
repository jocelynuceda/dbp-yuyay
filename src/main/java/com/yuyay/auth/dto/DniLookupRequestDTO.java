package com.yuyay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DniLookupRequestDTO(
        @NotBlank(message = "Ingresa tu DNI")
        @Pattern(regexp = "\\d{8}", message = "El DNI debe tener ocho dígitos") String dni
) {
    @Override
    public String toString() {
        return "DniLookupRequestDTO[reservado]";
    }
}
