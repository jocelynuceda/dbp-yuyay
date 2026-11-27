package com.yuyay.care.dto;

import java.time.Instant;

/**
 * Respuesta del contraste. <b>No incluye el nombre que devolvió el directorio</b>: si lo
 * incluyera, el endpoint sería un buscador de personas por DNI para cualquier cuenta con un perfil.
 */
public record IdentityCheckResponse(IdentityCheckResult result, Instant checkedAt) {}
