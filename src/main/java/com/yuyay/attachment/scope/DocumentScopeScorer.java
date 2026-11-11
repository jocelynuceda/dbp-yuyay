package com.yuyay.attachment.scope;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Regresión logística sobre TF-IDF de n-gramas de caracteres (char_wb), igual que el modelo entrenado.
 * Sin dependencias de Spring: es inmutable y se puede usar desde varios hilos.
 */
public final class DocumentScopeScorer {
    private final Map<String, Integer> vocabulary;
    private final double[] idf;
    private final double[] coef;
    private final double intercept;
    private final int minN;
    private final int maxN;
    private final int minAlnum;
    private final int minLetters;
    private final int maxChars;
    private final double nonmedicalMax;
    private final double medicalMin;
    private final double minCoverage;
    private final double strikeMinCoverage;

    public DocumentScopeScorer(Map<String, Integer> vocabulary, double[] idf, double[] coef, double intercept,
                               int minN, int maxN, int minAlnum, int minLetters, int maxChars,
                               double nonmedicalMax, double medicalMin, double minCoverage, double strikeMinCoverage) {
        if (vocabulary == null || vocabulary.isEmpty()) {
            throw new IllegalArgumentException("El vocabulario no puede estar vacío");
        }
        if (idf == null || coef == null || idf.length == 0 || idf.length != coef.length) {
            throw new IllegalArgumentException("idf y coef deben tener el mismo tamaño y no estar vacíos");
        }
        for (Map.Entry<String, Integer> entry : vocabulary.entrySet()) {
            Integer index = entry.getValue();
            if (entry.getKey() == null || index == null || index < 0 || index >= idf.length) {
                throw new IllegalArgumentException("Índice de vocabulario fuera de rango");
            }
        }
        for (int i = 0; i < idf.length; i++) {
            if (!Double.isFinite(idf[i]) || !Double.isFinite(coef[i])) {
                throw new IllegalArgumentException("idf y coef deben ser números finitos");
            }
        }
        if (!Double.isFinite(intercept)) {
            throw new IllegalArgumentException("El intercepto debe ser un número finito");
        }
        if (minN < 1 || maxN < minN) {
            throw new IllegalArgumentException("Rango de n-gramas inválido");
        }
        if (minAlnum < 0 || minLetters < 0 || maxChars < 1) {
            throw new IllegalArgumentException("Límites de texto inválidos");
        }
        if (!(nonmedicalMax >= 0.0) || !(medicalMin <= 1.0) || !(nonmedicalMax <= medicalMin)) {
            throw new IllegalArgumentException("Umbrales inválidos: se espera 0 <= nonmedicalMax <= medicalMin <= 1");
        }
        if (!(minCoverage >= 0.0 && minCoverage <= 1.0)) {
            throw new IllegalArgumentException("La cobertura mínima debe estar entre 0 y 1");
        }
        if (!(strikeMinCoverage >= minCoverage && strikeMinCoverage <= 1.0)) {
            throw new IllegalArgumentException("La cobertura mínima de un strike debe estar entre minCoverage y 1");
        }
        this.vocabulary = Map.copyOf(vocabulary);
        this.idf = idf.clone();
        this.coef = coef.clone();
        this.intercept = intercept;
        this.minN = minN;
        this.maxN = maxN;
        this.minAlnum = minAlnum;
        this.minLetters = minLetters;
        this.maxChars = maxChars;
        this.nonmedicalMax = nonmedicalMax;
        this.medicalMin = medicalMin;
        this.minCoverage = minCoverage;
        this.strikeMinCoverage = strikeMinCoverage;
    }

    public DocumentScopeResult classify(String text) {
        if (text == null) {
            return review(null, 0.0, "texto_insuficiente");
        }
        int[] cps = normalize(text);
        // Sin cobertura en textos demasiado largos: no se recorren.
        if (cps.length > maxChars) {
            return review(null, 0.0, "texto_demasiado_largo");
        }
        int alnum = 0;
        int letters = 0;
        for (int cp : cps) {
            if (Character.isLetter(cp)) {
                letters++;
                alnum++;
            } else if (isNumber(cp)) {
                alnum++;
            }
        }
        boolean insufficient = alnum < minAlnum || letters < minLetters;

        // Python lower() sobre el texto normalizado; los espacios simples separan las palabras.
        int[] lowered = toCodePoints(new String(cps, 0, cps.length).toLowerCase(Locale.ROOT));
        int[] counts = new int[idf.length];
        List<Integer> seen = new ArrayList<>();
        long total = 0;
        long known = 0;
        int start = 0;
        while (start < lowered.length) {
            while (start < lowered.length && isPythonWhitespace(lowered[start])) start++;
            int end = start;
            while (end < lowered.length && !isPythonWhitespace(lowered[end])) end++;
            if (end > start) {
                int[] padded = new int[end - start + 2];
                padded[0] = ' ';
                System.arraycopy(lowered, start, padded, 1, end - start);
                padded[padded.length - 1] = ' ';
                int size = padded.length;
                for (int n = minN; n <= maxN; n++) {
                    int offset = 0;
                    total++;
                    known += count(padded, 0, Math.min(n, size), counts, seen);
                    while (offset + n < size) {
                        offset++;
                        total++;
                        known += count(padded, offset, Math.min(n, size - offset), counts, seen);
                    }
                    if (offset == 0) break; // palabra más corta que n: cuenta una sola vez
                }
            }
            start = end;
        }
        double coverage = total == 0 ? 0.0 : (double) known / (double) total;
        if (insufficient) {
            return review(null, coverage, "texto_insuficiente");
        }
        if (known == 0) {
            return review(null, coverage, "sin_vocabulario_conocido");
        }

        double[] weights = new double[seen.size()];
        double sumSquares = 0.0;
        for (int i = 0; i < weights.length; i++) {
            int index = seen.get(i);
            weights[i] = (1.0 + Math.log(counts[index])) * idf[index];
            sumSquares += weights[i] * weights[i];
        }
        double scale = Math.sqrt(sumSquares);
        if (scale == 0.0) scale = 1.0;
        double margin = intercept;
        double dot = 0.0;
        for (int i = 0; i < weights.length; i++) {
            dot += weights[i] * coef[seen.get(i)];
        }
        margin += dot / scale;
        double score = logistic(margin);
        if (!(score >= 0.0 && score <= 1.0)) {
            return review(null, coverage, "puntaje_invalido");
        }
        if (coverage < minCoverage) {
            return review(score, coverage, "vocabulario_insuficiente");
        }
        if (score <= nonmedicalMax) {
            // Un strike pide más evidencia que las otras etiquetas.
            if (coverage < strikeMinCoverage) {
                return review(score, coverage, "vocabulario_insuficiente");
            }
            return new DocumentScopeResult(DocumentScopeLabel.NONMEDICAL, score, coverage, null);
        }
        if (score >= medicalMin) {
            return new DocumentScopeResult(DocumentScopeLabel.MEDICAL, score, coverage, null);
        }
        return review(score, coverage, "puntaje_intermedio");
    }

    private static DocumentScopeResult review(Double score, double coverage, String reason) {
        return new DocumentScopeResult(DocumentScopeLabel.REVIEW, score, coverage, reason);
    }

    /** Suma una aparición del n-grama si está en el vocabulario y devuelve 1 (o 0 si no está). */
    private int count(int[] padded, int offset, int length, int[] counts, List<Integer> seen) {
        Integer index = vocabulary.get(new String(padded, offset, length));
        if (index == null) return 0;
        if (counts[index]++ == 0) seen.add(index);
        return 1;
    }

    private static double logistic(double z) {
        double e = Math.exp(-Math.abs(z));
        return z >= 0 ? 1.0 / (1.0 + e) : e / (1.0 + e);
    }

    /** NFC y espacios colapsados a uno solo, como str.split() de Python. */
    private static int[] normalize(String text) {
        int[] cps = toCodePoints(Normalizer.normalize(text, Normalizer.Form.NFC));
        int[] out = new int[cps.length];
        int n = 0;
        boolean pendingSpace = false;
        for (int cp : cps) {
            if (isPythonWhitespace(cp)) {
                pendingSpace = n > 0;
            } else {
                if (pendingSpace) {
                    out[n++] = ' ';
                    pendingSpace = false;
                }
                out[n++] = cp;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static int[] toCodePoints(String s) {
        return s.codePoints().toArray();
    }

    private static boolean isNumber(int cp) {
        int type = Character.getType(cp);
        return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /** Espacios de str.split() en Python: Unicode White_Space más U+001C a U+001F. */
    private static boolean isPythonWhitespace(int cp) {
        if (cp >= 0x09 && cp <= 0x0D) return true;
        if (cp >= 0x1C && cp <= 0x20) return true;
        if (cp < 0x85) return false;
        return cp == 0x85 || cp == 0xA0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A)
                || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }
}
