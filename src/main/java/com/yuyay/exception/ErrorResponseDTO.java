package com.yuyay.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ErrorResponseDTO(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldErrorDTO> fieldErrors,
        @JsonInclude(JsonInclude.Include.NON_NULL) String code
) {
    public record FieldErrorDTO(String field, String message) {}

    public static ErrorResponseDTO of(int status, String error, String message, String path) {
        return new ErrorResponseDTO(Instant.now(), status, error, message, path, null, null);
    }
}
