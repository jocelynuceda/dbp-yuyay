package com.yuyay.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        return nameSimilarity(declaredName) == 1.0;
    }

    private static final Set<String> PARTICLES = Set.of("DE", "DEL", "LA", "LAS", "LOS");

    private record WordScore(double given, double family) {}

    /** 0..1. Permite omitir nombres del documento. Cada palabra escrita debe corresponder
     * al documento y dos posiciones distintas deben aportar un nombre y un apellido. */
    double nameSimilarity(String declaredName) {
        List<String> words = Names.tokens(declaredName);
        List<String> given = Names.tokens(nombres);
        List<String> family = Stream.concat(Names.tokens(apellidoPaterno).stream(),
                Names.tokens(apellidoMaterno).stream()).toList();
        List<WordScore> candidates = new ArrayList<>();
        double coverage = 1.0;
        for (String word : words) {
            if (PARTICLES.contains(word)) {
                if (!given.contains(word) && !family.contains(word)) return 0.0;
                continue;
            }
            WordScore score = new WordScore(bestSimilarity(word, given), bestSimilarity(word, family));
            candidates.add(score);
            coverage = Math.min(coverage, Math.max(score.given(), score.family()));
        }

        double bestPair = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            for (int k = 0; k < candidates.size(); k++) {
                if (i != k) {
                    bestPair = Math.max(bestPair, Math.min(candidates.get(i).given(), candidates.get(k).family()));
                }
            }
        }
        return Math.min(coverage, bestPair);
    }

    private static double bestSimilarity(String word, List<String> reference) {
        return reference.stream().filter(candidate -> !PARTICLES.contains(candidate))
                .mapToDouble(candidate -> Names.similarity(word, candidate)).max().orElse(0.0);
    }
}
