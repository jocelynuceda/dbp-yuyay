package com.yuyay.care.dto;

import com.yuyay.care.entity.CareAuthorizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CareAuthorizationUpdateRequest(
        @NotNull(message = "Selecciona un tipo de declaración") CareAuthorizationType type,
        @NotBlank(message = "Indica la versión del texto mostrado") String textVersion,
        Boolean confirmed,
        @Positive Long previousDeclarationId
) {
    public CareAuthorizationDeclarationRequest declaration() {
        return new CareAuthorizationDeclarationRequest(type, textVersion, confirmed);
    }
}
