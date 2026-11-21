package com.yuyay.care.dto;

import com.yuyay.care.entity.CareAuthorizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CareAuthorizationDeclarationRequest(
        @NotNull(message = "Selecciona un tipo de declaración") CareAuthorizationType type,
        @NotBlank(message = "Indica la versión del texto mostrado") String textVersion,
        Boolean confirmed
) {}
