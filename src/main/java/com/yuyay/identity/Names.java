package com.yuyay.identity;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normaliza un nombre a palabras comparables: sin tildes, sin ñ, sin puntuación, en mayúsculas.
 * Dos escrituras del mismo nombre deben producir las mismas palabras.
 */
final class Names {

    private static final int MIN_LENGTH = 2;

    private Names() {
    }

    static Set<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        // Las iniciales de una sola letra no identifican a nadie y sobran en la comparación.
        return Arrays.stream(folded.split("[^A-Z]+"))
                .filter(token -> token.length() >= MIN_LENGTH)
                .collect(Collectors.toUnmodifiableSet());
    }
}
