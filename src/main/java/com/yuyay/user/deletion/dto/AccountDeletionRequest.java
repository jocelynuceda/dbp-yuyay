package com.yuyay.user.deletion.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * La confirmación del borrado. {@code profileIds} tiene que repetir exactamente los perfiles que la
 * vista previa dijo que se pierden: así el servidor sabe que quien confirma vio la consecuencia, y
 * rechaza la confirmación si la lista cambió mientras decidía.
 */
public record AccountDeletionRequest(
        @NotBlank(message = "Falta la palabra de confirmación")
        String confirmation,
        List<Long> profileIds
) {
}
