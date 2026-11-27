package com.yuyay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DniLookupRequestDTO(
        @NotBlank(message = "Ingresa tu DNI")
        @Pattern(regexp = "[0-9]{8}", message = "El DNI debe tener ocho dígitos") String dni
) {
    /** El DNI no se escribe en logs. */
    @Override
    public String toString() {
        return "DniLookupRequestDTO[reservado]";
    }
}
