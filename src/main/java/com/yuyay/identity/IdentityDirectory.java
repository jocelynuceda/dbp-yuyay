package com.yuyay.identity;

/**
 * Consulta de datos de identidad por DNI.
 *
 * <p>Comprobar un DNI confirma que ese número existe y a qué nombre figura. <b>No confirma que
 * quien lo usa sea esa persona</b>: no es verificación de identidad y el producto nunca debe
 * presentarlo como tal.
 *
 * <p>El DNI es dato personal: no se persiste y no se escribe en logs. Lo que se guarda es el
 * resultado de la comprobación, no el número consultado.
 */
public interface IdentityDirectory {

    /**
     * @param dni ocho dígitos
     * @throws IllegalArgumentException si el DNI no tiene el formato esperado
     */
    IdentityLookup findByDni(String dni);

    /** Falso cuando no hay proveedor configurado: ninguna consulta puede responderse. */
    default boolean isConfigured() {
        return true;
    }
}
