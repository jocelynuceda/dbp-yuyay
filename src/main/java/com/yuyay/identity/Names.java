package com.yuyay.identity;

import org.apache.commons.text.similarity.LevenshteinDistance;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Normaliza un nombre a palabras comparables: sin tildes, sin ñ, sin puntuación, en mayúsculas.
 * Dos escrituras del mismo nombre deben producir las mismas palabras.
 */
final class Names {

    private static final int MIN_LENGTH = 2;

    private Names() {
    }

    static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        // Las iniciales de una sola letra no identifican a nadie y sobran en la comparación.
        return Arrays.stream(folded.split("[^A-Z]+"))
                .filter(token -> token.length() >= MIN_LENGTH)
                .toList();
    }

    static double similarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        int distance = LevenshteinDistance.getDefaultInstance().apply(left, right);
        return 1.0 - (double) distance / Math.max(left.length(), right.length());
    }
}
