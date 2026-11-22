package com.yuyay.consultation;

import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.entity.HandoffSession;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP, seguridad y persistencia reales en H2; solamente el almacenamiento de objetos se simula. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandoffSourceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AttachmentRepository attachments;
    @Autowired CareSubjectRepository subjects;
    @Autowired UserRepository users;
    @Autowired HandoffSessionRepository sessions;
    @Autowired HealthEntryVersionRepository versions;
    @MockitoBean FileStorage fileStorage;

    private static final byte[] ORIGINAL = "%PDF-1.7\nDocumento original".getBytes(StandardCharsets.UTF_8);
    private String ownerToken;
    private long ownerId;
    private long subjectId;
    private Attachment source;
    private long entryId;
    private long versionId;
    private long handoffId;
    private long sessionId;
    private String token;
    private final String reviewedText = "Texto revisado: presión, dosis y antecedentes.\n".repeat(170) + "FIN REVISADO";

    @BeforeEach
    void setUp() throws Exception {
        String email = "handoff-source-" + UUID.randomUUID() + "@yuyay.app";
        ownerToken = send(post("/api/v1/auth/register"), null, Map.of(
                "email", email, "name", "Cuidadora", "password", "Secreta123",
                "termsAccepted", true, "termsVersion", "1.0"), 201).get("accessToken").asText();
        ownerId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        subjectId = createSubject();
        source = createAttachment(subjectId);
        JsonNode entry = createEntry(subjectId, source.getId(), reviewedText);
        entryId = entry.get("id").asLong();
        versionId = entry.get("latestVersion").get("id").asLong();
        JsonNode handoff = send(post("/api/v1/care-subjects/" + subjectId + "/handoffs"), ownerToken,
                Map.of("reason", "Consulta", "healthEntryIds", List.of(entryId)), 201);
        handoffId = handoff.get("id").asLong();
        assertTrue(handoff.get("items").get(0).get("hasSourceAttachment").asBoolean());
        JsonNode session = send(post("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken,
                Map.of("expiresInHours", 1, "sessionWindowMinutes", 15), 201);
        token = session.get("token").asText();
        sessionId = session.get("id").asLong();
    }

    @Test
    void expectedVersionsPreventSharingUnreviewedChangesAndRemainOptional() throws Exception {
        String path = "/api/v1/care-subjects/" + subjectId + "/handoffs";
        Map<String, Object> reviewedRequest = Map.of("reason", "Revisado", "healthEntryIds", List.of(entryId),
                "expectedVersionIds", Map.of(Long.toString(entryId), versionId));
        send(post(path), ownerToken, reviewedRequest, 201);
        send(post(path), ownerToken, Map.of("reason", "Incompleto", "healthEntryIds", List.of(entryId),
                "expectedVersionIds", Map.of()), 400);
        send(post(path), ownerToken, Map.of("reason", "Ajeno", "healthEntryIds", List.of(entryId),
                "expectedVersionIds", Map.of("99999999", versionId)), 400);

        JsonNode updated = send(patch(entries(subjectId) + "/" + entryId), ownerToken,
                Map.of("title", "Cambio posterior", "details", "No revisado", "confidenceLevel", "CONFIRMED"), 200);
        send(post(path), ownerToken, reviewedRequest, 409);
        JsonNode legacy = send(post(path), ownerToken,
                Map.of("reason", "Cliente anterior", "healthEntryIds", List.of(entryId)), 201);
        assertEquals(updated.get("latestVersion").get("id").asLong(),
                legacy.get("items").get(0).get("healthEntryVersionId").asLong());
    }

    @Test
    void reviewedTextAndOriginalBelongToTheFrozenVersion() throws Exception {
        assertTrue(reviewedText.length() > 2000);
        assertEquals(reviewedText, versions.findById(versionId).orElseThrow().getDetails());
        String updatedText = "Texto corregido después de compartir.\n".repeat(130);
        JsonNode updated = send(patch(entries(subjectId) + "/" + entryId), ownerToken,
                Map.of("title", "Informe revisado", "details", updatedText, "confidenceLevel", "CONFIRMED"), 200);
        long newVersionId = updated.get("latestVersion").get("id").asLong();
        assertEquals(updatedText, versions.findById(newVersionId).orElseThrow().getDetails());
        assertEquals(source.getId().longValue(), updated.get("latestVersion").get("sourceAttachmentId").asLong());

        JsonNode shared = open();
        JsonNode item = shared.get("items").get(0);
        assertEquals(versionId, item.get("healthEntryVersionId").asLong());
        assertEquals(reviewedText, item.get("details").asText());
        assertTrue(item.get("hasSourceAttachment").asBoolean());
        assertFalse(item.has("sourceAttachmentId"));
        assertFalse(item.has("storageKey"));
        assertEquals(reviewedText, send(get("/api/v1/handoffs/" + handoffId), ownerToken, null, 200)
                .get("items").get(0).get("details").asText());

        when(fileStorage.load(source.getStorageKey())).thenReturn(ORIGINAL);
        mvc.perform(get(contentUrl(versionId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(ORIGINAL))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));
        mvc.perform(get(contentUrl(versionId)).header("Range", "bytes=0-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-7/" + ORIGINAL.length))
                .andExpect(content().bytes(java.util.Arrays.copyOf(ORIGINAL, 8)));
        verify(fileStorage, times(2)).load(source.getStorageKey());
        send(get(contentUrl(newVersionId)), null, null, 404);
    }

    @Test
    void requiresOpeningAndRejectsUnknownToken() throws Exception {
        send(get(contentUrl(versionId)), null, null, 403);
        send(get("/api/v1/public/handoff-sessions/unknown/versions/" + versionId + "/source/content"), null, null, 404);
        send(post("/api/v1/public/handoff-sessions/" + token + "/open"), null,
                Map.of("visitorName", "", "visitorRole", ""), 400);
        send(get(contentUrl(versionId)), null, null, 403);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void revokedSessionCannotFetchOriginal() throws Exception {
        open();
        send(delete("/api/v1/handoff-sessions/" + sessionId), ownerToken, null, 204);
        send(get(contentUrl(versionId)), null, null, 410);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void expirationAndOpenedWindowAreBothEnforcedOnEveryFetch() throws Exception {
        open();
        HandoffSession session = sessions.findById(sessionId).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(1));
        sessions.saveAndFlush(session);
        send(get(contentUrl(versionId)), null, null, 410);

        session.setExpiresAt(Instant.now().plusSeconds(3600));
        session.setFirstOpenedAt(Instant.now().minusSeconds(16 * 60));
        sessions.saveAndFlush(session);
        send(get(contentUrl(versionId)), null, null, 410);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void excludesUnsharedEntriesOtherSubjectsAndVersionsWithoutSource() throws Exception {
        JsonNode unshared = createEntry(subjectId, createAttachment(subjectId).getId(), "No compartido");
        long otherSubject = createSubject();
        JsonNode foreign = createEntry(otherSubject, createAttachment(otherSubject).getId(), "Otra persona");
        JsonNode noSource = createEntry(subjectId, null, "Sin documento");
        open();
        send(get(contentUrl(unshared.get("latestVersion").get("id").asLong())), null, null, 404);
        send(get(contentUrl(foreign.get("latestVersion").get("id").asLong())), null, null, 404);

        JsonNode noSourceHandoff = send(post("/api/v1/care-subjects/" + subjectId + "/handoffs"), ownerToken,
                Map.of("reason", "Sin evidencia", "healthEntryIds", List.of(noSource.get("id").asLong())), 201);
        assertFalse(noSourceHandoff.get("items").get(0).get("hasSourceAttachment").asBoolean());
        JsonNode session = send(post("/api/v1/handoffs/" + noSourceHandoff.get("id").asLong() + "/sessions"), ownerToken,
                Map.of("expiresInHours", 1, "sessionWindowMinutes", 15), 201);
        token = session.get("token").asText();
        open();
        send(get(contentUrl(noSource.get("latestVersion").get("id").asLong())), null, null, 404);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void rejectsForeignAttachmentEvenIfAnInvalidRelationWasPersisted() throws Exception {
        // Defensa ante datos heredados o corruptos: no basta con que la versión pertenezca al resumen.
        Attachment foreign = createAttachment(createSubject());
        var version = versions.findById(versionId).orElseThrow();
        version.setSourceAttachment(foreign);
        versions.saveAndFlush(version);
        open();
        send(get(contentUrl(versionId)), null, null, 404);
        verifyNoInteractions(fileStorage);
    }

    private JsonNode open() throws Exception {
        return send(post("/api/v1/public/handoff-sessions/" + token + "/open"), null,
                Map.of("visitorName", "Dra. Visitante", "visitorRole", "Médica"), 200);
    }

    private long createSubject() throws Exception {
        return send(post("/api/v1/care-subjects"), ownerToken, Map.of(
                "requestId", UUID.randomUUID().toString(), "name", "Paciente ficticio",
                "declaration", Map.of("type", "FICTITIOUS", "textVersion", "1", "confirmed", true)), 201)
                .get("id").asLong();
    }

    private Attachment createAttachment(long careSubjectId) {
        return attachments.saveAndFlush(Attachment.builder()
                .careSubject(subjects.findById(careSubjectId).orElseThrow())
                .uploadedBy(users.findById(ownerId).orElseThrow())
                .originalFilename("informe.pdf").contentType("application/pdf")
                .sizeBytes((long) ORIGINAL.length).storageKey("handoff-tests/" + UUID.randomUUID() + ".pdf")
                .ocrStatus(OcrStatus.DONE).ocrText("OCR sin revisar").build());
    }

    private JsonNode createEntry(long careSubjectId, Long attachmentId, String details) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("categoryCode", "CONDITION");
        body.put("title", "Informe revisado");
        body.put("details", details);
        body.put("confidenceLevel", "CONFIRMED");
        if (attachmentId != null) body.put("sourceAttachmentId", attachmentId);
        return send(post(entries(careSubjectId)), ownerToken, body, 201);
    }

    private String entries(long careSubjectId) {
        return "/api/v1/care-subjects/" + careSubjectId + "/health-entries";
    }

    private String contentUrl(long healthEntryVersionId) {
        return "/api/v1/public/handoff-sessions/" + token + "/versions/" + healthEntryVersionId + "/source/content";
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String authToken, Object body, int expected) throws Exception {
        if (authToken != null) request.header("Authorization", "Bearer " + authToken);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isEmpty() ? null : json.readTree(response);
    }
}
