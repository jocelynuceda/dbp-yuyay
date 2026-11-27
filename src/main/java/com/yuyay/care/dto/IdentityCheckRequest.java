package com.yuyay.care.dto;

import jakarta.validation.constraints.Pattern;

/** El DNI solo transita: no se persiste ni se escribe en logs. */
public record IdentityCheckRequest(
        @Pattern(regexp = "\\d{8}", message = "El DNI debe tener ocho dígitos") String dni
) {}
