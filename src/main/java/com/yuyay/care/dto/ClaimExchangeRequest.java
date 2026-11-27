package com.yuyay.care.dto;

import jakarta.validation.constraints.NotBlank;

public record ClaimExchangeRequest(
        @NotBlank(message = "Falta el enlace de entrega") String token
) {}
