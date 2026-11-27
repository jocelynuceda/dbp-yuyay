package com.yuyay.identity;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Datos de identidad de un DNI tal como los devuelve el directorio consultado.
 *
 * <p>No incluye fecha de nacimiento: la consulta de DNI de APIsPERU no la entrega, y la compuerta
 * de edad del canje se resuelve con la fecha declarada en el perfil. Lo que este registro permite
 * es contrastar el nombre. Ver {@code docs/modelo-autocuidado-y-recordatorios.md} §3.5.
 */
public record IdentityRecord(
        String nombres,
        String apellidoPaterno,
        String apellidoMaterno
) {
    /** Nombre completo en el orden en que lo escribe RENIEC. */
    public String fullName() {
        return String.join(" ", nombres, apellidoPaterno, apellidoMaterno).strip().replaceAll("\\s+", " ");
    }

    /**
     * Contrasta el nombre escrito en el perfil con el que figura en el DNI.
     *
     * <p>Coincide cuando el nombre declarado no agrega ninguna palabra ajena al DNI y contiene al
     * menos un nombre y al menos un apellido. Así «Ana Quispe» coincide con «ANA MARIA QUISPE
     * ROJAS» —escribir el nombre corto es lo normal—, mientras que «Ana» o «Quispe Rojas» no
     * alcanzan para afirmar que se trata de la misma persona, y «Ana María Pérez» se rechaza.
     *
     * <p>Se ignoran mayúsculas, tildes, la letra ñ, la puntuación, el orden de las palabras y las
     * iniciales de una sola letra. <b>Coincidir no acredita identidad</b>: dentro de una familia el
     * nombre y el DNI se conocen. Reduce el error de tipeo y el perfil inventado, nada más.
     */
    public boolean matchesName(String declaredName) {
        Set<String> declared = Names.tokens(declaredName);
        if (declared.isEmpty()) {
            return false;
        }
        Set<String> given = Names.tokens(nombres);
        Set<String> family = Stream.concat(
                        Names.tokens(apellidoPaterno).stream(),
                        Names.tokens(apellidoMaterno).stream())
                .collect(Collectors.toUnmodifiableSet());

        boolean nothingInvented = Stream.concat(given.stream(), family.stream())
                .collect(Collectors.toUnmodifiableSet())
                .containsAll(declared);

        return nothingInvented
                && declared.stream().anyMatch(given::contains)
                && declared.stream().anyMatch(family::contains);
    }
}
