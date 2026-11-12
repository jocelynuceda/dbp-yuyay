package com.yuyay.attachment.scope;

import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.OcrStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Etiqueta el texto OCR como MEDICAL, REVIEW o NONMEDICAL. Viene apagado: con app.document-scope.enabled=false
 * (o si el modelo no carga) no hace nada y las respuestas salen sin etiqueta. Un administrador puede cambiarlo en caliente.
 */
@Slf4j
@Service
public class DocumentScopeService {
    private static final String MODEL_PATH = "document-scope/model.json";
    private static final String SETTINGS_PATH = "document-scope/classifier.json";

    private final ObjectMapper objectMapper;
    private final double minCoverage;
    private final double strikeMinCoverage;
    private volatile boolean enabled;
    private volatile DocumentScopeScorer scorer;

    public DocumentScopeService(ObjectMapper objectMapper,
                                @Value("${app.document-scope.enabled:false}") boolean enabled,
                                @Value("${app.document-scope.min-coverage:0.50}") double minCoverage,
                                @Value("${app.document-scope.strike-min-coverage:0.65}") double strikeMinCoverage) {
        this.objectMapper = objectMapper;
        this.minCoverage = minCoverage;
        this.strikeMinCoverage = strikeMinCoverage;
        if (enabled) {
            this.scorer = load(objectMapper, minCoverage, strikeMinCoverage);
        }
        this.enabled = this.scorer != null;
    }

    /** Enciende o apaga el clasificador en caliente. Si el modelo no carga, queda apagado. */
    public synchronized boolean setEnabled(boolean value) {
        if (value && scorer == null) {
            scorer = load(objectMapper, minCoverage, strikeMinCoverage);
        }
        this.enabled = value && scorer != null;
        return isEnabled();
    }

    /** Nunca lanza: cualquier fallo al cargar deja el clasificador apagado para no impedir el arranque. */
    private static DocumentScopeScorer load(ObjectMapper objectMapper, double minCoverage,
                                                double strikeMinCoverage) {
        try {
            Map<?, ?> model = read(objectMapper, MODEL_PATH);
            Map<?, ?> settings = read(objectMapper, SETTINGS_PATH);
            double[] range = doubles(model, "ngram_range");
            if (!"tfidf_logreg_v1".equals(string(model, "format")) || !"char_wb".equals(string(model, "analyzer"))
                    || !flag(model, "lowercase") || !flag(model, "sublinear_tf") || !"l2".equals(string(model, "norm"))
                    || range.length != 2) {
                throw new IllegalStateException("Formato de modelo no soportado");
            }
            DocumentScopeScorer loaded = new DocumentScopeScorer(vocabulary(model), doubles(model, "idf"),
                    doubles(model, "coef"), number(model, "intercept").doubleValue(),
                    (int) range[0], (int) range[1],
                    number(settings, "min_alnum").intValue(), number(settings, "min_letters").intValue(),
                    number(settings, "max_chars").intValue(),
                    number(settings, "nonmedical_max").doubleValue(), number(settings, "medical_min").doubleValue(),
                    minCoverage, strikeMinCoverage);
            log.info("Clasificador de alcance de documentos activado");
            return loaded;
        } catch (Exception e) {
            log.warn("No se pudo cargar el clasificador de alcance de documentos: {}. Queda desactivado",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private static Map<?, ?> read(ObjectMapper objectMapper, String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(in, Map.class);
        }
    }

    private static Object required(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null) throw new IllegalStateException("Falta la clave " + key);
        return value;
    }

    private static Number number(Map<?, ?> raw, String key) {
        if (required(raw, key) instanceof Number value) return value;
        throw new IllegalStateException("Tipo inválido en " + key);
    }

    private static String string(Map<?, ?> raw, String key) {
        return String.valueOf(required(raw, key));
    }

    private static boolean flag(Map<?, ?> raw, String key) {
        return Boolean.TRUE.equals(required(raw, key));
    }

    private static double[] doubles(Map<?, ?> raw, String key) {
        if (!(required(raw, key) instanceof List<?> list)) throw new IllegalStateException("Tipo inválido en " + key);
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            if (!(list.get(i) instanceof Number value)) throw new IllegalStateException("Tipo inválido en " + key);
            out[i] = value.doubleValue();
        }
        return out;
    }

    private static Map<String, Integer> vocabulary(Map<?, ?> raw) {
        if (!(required(raw, "vocabulary") instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Tipo inválido en vocabulary");
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Number index)) {
                throw new IllegalStateException("Entrada inválida en vocabulary");
            }
            out.put(key, index.intValue());
        }
        return out;
    }

    public boolean isEnabled() {
        return enabled && scorer != null;
    }

    public boolean isModelLoaded() {
        return scorer != null;
    }

    public Optional<DocumentScopeResult> classify(String ocrText) {
        DocumentScopeScorer current = enabled ? scorer : null;
        if (current == null) return Optional.empty();
        return Optional.of(current.classify(ocrText));
    }

    public AttachmentResponse annotate(AttachmentResponse response) {
        DocumentScopeScorer current = enabled ? scorer : null;
        if (current == null || response == null || response.ocrStatus() != OcrStatus.DONE || isAudio(response)) {
            return response;
        }
        try {
            DocumentScopeResult result = current.classify(response.ocrText());
            return new AttachmentResponse(response.id(), response.careSubjectId(), response.consultationId(),
                    response.uploadedById(), response.uploadedByName(), response.originalFilename(),
                    response.contentType(), response.sizeBytes(), response.ocrStatus(), response.ocrText(),
                    response.ocrError(), response.uploadedAt(), response.ocrCompletedAt(),
                    result.label(), result.score());
        } catch (RuntimeException e) {
            log.warn("No se pudo clasificar el adjunto {}: {}", response.id(), e.getClass().getSimpleName());
            return response;
        }
    }

    public List<AttachmentResponse> annotate(List<AttachmentResponse> responses) {
        if (!isEnabled() || responses == null) return responses;
        return responses.stream().map(this::annotate).toList();
    }

    private static boolean isAudio(AttachmentResponse response) {
        String type = response.contentType();
        return type != null && type.startsWith("audio/");
    }
}
