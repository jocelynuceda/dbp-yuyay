package com.yuyay.identity;

/**
 * Resultado de consultar un DNI. Los tres casos se distinguen a propósito: «no existe» rechaza la
 * operación, mientras que «no disponible» la deja pendiente de verificación en vez de bloquearla.
 * Un proveedor caído no puede ser la razón por la que alguien no accede a su propio historial.
 */
public sealed interface IdentityLookup {

    record Found(IdentityRecord person) implements IdentityLookup {}

    record NotFound() implements IdentityLookup {}

    /**
     * El directorio no respondió, rechazó la credencial o devolvió algo que no se pudo leer.
     * {@code noResponse} distingue la espera agotada o la conexión fallida del resto.
     */
    record Unavailable(String reason, boolean noResponse) implements IdentityLookup {
        public Unavailable(String reason) {
            this(reason, false);
        }
    }
}
