package com.yuyay.attachment.scope;

/** Resultado de clasificar un texto. score es null si no se pudo calcular; reason es null salvo en REVIEW. */
public record DocumentScopeResult(
        DocumentScopeLabel label,
        Double score,
        double coverage,
        String reason
) {}
