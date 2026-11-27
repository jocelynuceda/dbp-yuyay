package com.yuyay.identity;

import java.util.List;
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
    private static final Set<String> PARTICLES = Set.of("DE", "DEL", "LA", "LAS", "LOS");

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
        List<String> words = Names.tokens(declaredName);
        Set<String> given = Set.copyOf(Names.tokens(nombres));
        Set<String> family = Stream.concat(Names.tokens(apellidoPaterno).stream(), Names.tokens(apellidoMaterno).stream())
                .collect(Collectors.toSet());

        boolean seenGiven = false;
        boolean seenFamily = false;
        boolean hasPair = false;
        for (String word : words) {
            boolean isGiven = given.contains(word);
            boolean isFamily = family.contains(word);
            if (!isGiven && !isFamily) {
                return false;
            }
            if (!PARTICLES.contains(word)) {
                // El nombre y el apellido deben provenir de dos posiciones distintas.
                hasPair |= (isFamily && seenGiven) || (isGiven && seenFamily);
                seenGiven |= isGiven;
                seenFamily |= isFamily;
            }
        }
        return hasPair;
    }
}
