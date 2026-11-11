package com.yuyay.attachment;

import com.yuyay.attachment.dto.AttachmentResponse;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.scope.DocumentScopeLabel;
import com.yuyay.attachment.scope.DocumentScopeResult;
import com.yuyay.attachment.scope.DocumentScopeService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentScopeServiceTest {
    private static final String PRESCRIPTION =
            "Rp. Amoxicilina 500 mg cápsulas. Tomar 1 cada 8 horas por 7 días. Dr. Mario Demo CMP 00001";
    private static final String MENU = "Menú del día: sopa de pollo, arroz con pollo, refresco de maracuyá. S/ 12.00";
    private static final String GIBBERISH = "zzzzqqqq wwwwkkkk xxxxjjjj vvvvyyyy 1234567890";

    private final DocumentScopeService enabled = new DocumentScopeService(JsonMapper.builder().build(), true, 0.50, 0.65);
    private final DocumentScopeService disabled = new DocumentScopeService(JsonMapper.builder().build(), false, 0.50, 0.65);

    /** Cada fila trae el puntaje calculado por la referencia en Python con el mismo modelo. */
    @Test
    void scoresMatchThePythonReferenceForEveryParityRow() throws IOException {
        assertTrue(enabled.isEnabled(), "el modelo empaquetado debe cargar");
        int rows = 0;
        try (InputStream in = getClass().getResourceAsStream("/document-scope/parity.tsv")) {
            assertNotNull(in, "falta document-scope/parity.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                String text = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                DocumentScopeResult result = enabled.classify(text).orElseThrow();
                String row = "fila " + (rows + 1);

                if ("null".equals(parts[1])) {
                    assertNull(result.score(), row);
                } else {
                    assertNotNull(result.score(), row);
                    assertEquals(Double.parseDouble(parts[1]), result.score(), 1e-9, row);
                }
                assertEquals(Double.parseDouble(parts[2]), result.coverage(), 1e-9, row);
                assertEquals(DocumentScopeLabel.valueOf(parts[3]), result.label(), row);
                assertEquals("-".equals(parts[4]) ? null : parts[4], result.reason(), row);
                rows++;
            }
        }
        assertEquals(30, rows);
    }

    @Test
    void labelsPrescriptionAsMedicalAndUnrelatedTextAsNonmedical() {
        assertEquals(DocumentScopeLabel.MEDICAL, enabled.classify(PRESCRIPTION).orElseThrow().label());
        assertEquals(DocumentScopeLabel.NONMEDICAL, enabled.classify(MENU).orElseThrow().label());
    }

    /** Un texto que el modelo casi no reconoce (OCR ilegible) nunca cuenta como uso ajeno. */
    @Test
    void textTheModelBarelyRecognisesGoesToReviewInsteadOfNonmedical() {
        DocumentScopeResult result = enabled.classify(GIBBERISH).orElseThrow();

        assertEquals(DocumentScopeLabel.REVIEW, result.label());
        assertEquals("vocabulario_insuficiente", result.reason());
        assertTrue(result.coverage() < 0.50);
    }

    /**
     * 72 textos que el modelo nunca vio al entrenar: documentos de salud poco típicos (dieta, salud mental,
     * rehabilitación, dental, manuscritos mal leídos) y textos ajenos, con trampas de palabras clave e ilegibles.
     * El error grave es marcar como uso ajeno un documento de salud: no debe ocurrir en ninguno.
     */
    @Test
    void heldOutDocumentsNeverGetAStrikeWhenTheyAreHealthDocuments() throws IOException {
        int rows = 0;
        int healthMarkedAsStrike = 0;
        int unrelatedMarkedAsMedical = 0;
        try (InputStream in = getClass().getResourceAsStream("/document-scope/held-out.tsv")) {
            assertNotNull(in, "falta document-scope/held-out.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                String name = parts[0];
                boolean health = "medical".equals(parts[1]);
                String text = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                DocumentScopeResult result = enabled.classify(text).orElseThrow();

                assertEquals(DocumentScopeLabel.valueOf(parts[5]), result.label(), name);
                if (!"null".equals(parts[3])) {
                    assertEquals(Double.parseDouble(parts[3]), result.score(), 1e-9, name);
                }
                if (result.label() == DocumentScopeLabel.NONMEDICAL) {
                    assertTrue(result.coverage() >= 0.65, name + ": un strike exige reconocer al menos el 65 % del texto");
                }
                if (health && result.label() == DocumentScopeLabel.NONMEDICAL) healthMarkedAsStrike++;
                if (!health && result.label() == DocumentScopeLabel.MEDICAL) unrelatedMarkedAsMedical++;
                rows++;
            }
        }
        assertEquals(72, rows);
        assertEquals(0, healthMarkedAsStrike);
        assertEquals(4, unrelatedMarkedAsMedical, "errores tolerables: palabras clave repetidas, un tarifario y un ilegible");
    }

    @Test
    void theSwitchTurnsOnAndOffAtRuntime() {
        DocumentScopeService service = new DocumentScopeService(JsonMapper.builder().build(), false, 0.50, 0.65);
        AttachmentResponse response = response(OcrStatus.DONE, "image/png", PRESCRIPTION);
        assertFalse(service.isEnabled());
        assertFalse(service.isModelLoaded());
        assertSame(response, service.annotate(response));

        assertTrue(service.setEnabled(true), "al encender carga el modelo");
        assertTrue(service.isEnabled());
        assertTrue(service.isModelLoaded());
        assertEquals(DocumentScopeLabel.MEDICAL, service.annotate(response).scopeLabel());

        assertFalse(service.setEnabled(false));
        assertFalse(service.isEnabled());
        assertTrue(service.isModelLoaded(), "el modelo queda en memoria para volver a encender al instante");
        assertSame(response, service.annotate(response));
        assertEquals(Optional.empty(), service.classify(PRESCRIPTION));

        assertTrue(service.setEnabled(true));
        assertEquals(DocumentScopeLabel.MEDICAL, service.classify(PRESCRIPTION).orElseThrow().label());
    }

    @Test
    void emptyOrMissingTextGoesToReview() {
        assertEquals(DocumentScopeLabel.REVIEW, enabled.classify(null).orElseThrow().label());
        assertEquals(DocumentScopeLabel.REVIEW, enabled.classify("").orElseThrow().label());
        assertEquals("texto_insuficiente", enabled.classify("ab").orElseThrow().reason());
    }

    @Test
    void switchedOffItDoesNothing() {
        AttachmentResponse response = response(OcrStatus.DONE, "image/png", PRESCRIPTION);

        assertFalse(disabled.isEnabled());
        assertEquals(Optional.empty(), disabled.classify(PRESCRIPTION));
        assertSame(response, disabled.annotate(response));
        List<AttachmentResponse> list = List.of(response);
        assertSame(list, disabled.annotate(list));
    }

    @Test
    void annotateAddsLabelAndScoreOnlyToDocumentsWithFinishedOcr() {
        AttachmentResponse done = enabled.annotate(response(OcrStatus.DONE, "image/png", PRESCRIPTION));
        assertEquals(DocumentScopeLabel.MEDICAL, done.scopeLabel());
        assertNotNull(done.scopeScore());
        assertEquals(PRESCRIPTION, done.ocrText());
        assertEquals(Long.valueOf(7L), done.id());

        AttachmentResponse pending = response(OcrStatus.PENDING, "image/png", null);
        assertSame(pending, enabled.annotate(pending));
        AttachmentResponse failed = response(OcrStatus.FAILED, "application/pdf", null);
        assertSame(failed, enabled.annotate(failed));
        AttachmentResponse audio = response(OcrStatus.DONE, "audio/mp4", null);
        assertSame(audio, enabled.annotate(audio));
        assertNull(enabled.annotate((AttachmentResponse) null));
    }

    @Test
    void annotateListKeepsOrder() {
        List<AttachmentResponse> annotated = enabled.annotate(List.of(
                response(OcrStatus.DONE, "image/png", MENU),
                response(OcrStatus.PENDING, "image/png", null),
                response(OcrStatus.DONE, "application/pdf", PRESCRIPTION)));

        assertEquals(DocumentScopeLabel.NONMEDICAL, annotated.get(0).scopeLabel());
        assertNull(annotated.get(1).scopeLabel());
        assertEquals(DocumentScopeLabel.MEDICAL, annotated.get(2).scopeLabel());
    }

    private static AttachmentResponse response(OcrStatus status, String contentType, String ocrText) {
        return new AttachmentResponse(7L, 1L, null, 5L, "Ana", "documento", contentType, 100L, status, ocrText,
                null, Instant.parse("2026-11-10T00:00:00Z"), null, null, null);
    }
}
