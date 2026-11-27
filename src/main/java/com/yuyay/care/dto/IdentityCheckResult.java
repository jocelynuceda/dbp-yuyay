package com.yuyay.care.dto;

/**
 * Resultado de contrastar un DNI con el nombre de un perfil.
 *
 * <p>Los cuatro casos se distinguen a propósito: {@code MISMATCH} es un dato que no coincide,
 * {@code NOT_FOUND} un documento que no existe y {@code UNAVAILABLE} un servicio que no respondió.
 * Confundir el último con los otros dos haría que una caída del proveedor pareciera un rechazo.
 */
public enum IdentityCheckResult {
    MATCH,
    MISMATCH,
    NOT_FOUND,
    UNAVAILABLE
}
