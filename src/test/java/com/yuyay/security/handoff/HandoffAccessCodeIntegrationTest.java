package com.yuyay.security.handoff;

import com.yuyay.attachment.entity.Attachment;
import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.repository.AttachmentRepository;
import com.yuyay.attachment.storage.FileStorage;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.consultation.repository.HandoffSessionRepository;
import com.yuyay.consultation.service.HandoffSessionService;
import com.yuyay.security.TokenHasher;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP, JPA y bloqueos reales. Sólo se reemplazan reloj y almacenamiento de objetos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandoffAccessCodeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AttachmentRepository attachments;
    @Autowired CareSubjectRepository subjects;
    @Autowired UserRepository users;
    @Autowired CareRelationshipRepository relationships;
    @Autowired HandoffSessionRepository sessions;
    @Autowired HandoffCodeStateRepository codeStates;
    @Autowired HandoffAccessGrantRepository grants;
    @Autowired HandoffSessionService handoffService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean FileStorage fileStorage;
    @MockitoBean Clock clock;

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final byte[] PDF = "%PDF-1.7\nDocumento".getBytes(StandardCharsets.UTF_8);
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private String ownerToken;
    private long ownerId;
    private long subjectId;
    private long handoffId;
    private long versionId;
    private long sessionId;
    private String token;
    private String storageKey;

    @BeforeEach
    void setUp() throws Exception {
        now.set(START);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        String email = "code-" + UUID.randomUUID() + "@yuyay.app";
        ownerToken = register(email);
        ownerId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        subjectId = send(post("/api/v1/care-subjects"), ownerToken, Map.of(
                "requestId", UUID.randomUUID().toString(), "name", "Paciente ficticio",
                "declaration", Map.of("type", "FICTITIOUS", "textVersion", "1", "confirmed", true)), 201)
                .get("id").asLong();
        storageKey = "code-test/" + UUID.randomUUID() + ".pdf";
        Attachment source = attachments.saveAndFlush(Attachment.builder()
                .careSubject(subjects.findById(subjectId).orElseThrow())
                .uploadedBy(users.findById(ownerId).orElseThrow()).originalFilename("informe.pdf")
                .contentType("application/pdf").sizeBytes((long) PDF.length).storageKey(storageKey)
                .ocrStatus(OcrStatus.DONE).build());
        JsonNode entry = send(post("/api/v1/care-subjects/" + subjectId + "/health-entries"), ownerToken,
                Map.of("categoryCode", "CONDITION", "title", "Informe", "details", "Texto revisado",
                        "sourceAttachmentId", source.getId(), "confidenceLevel", "CONFIRMED"), 201);
        versionId = entry.get("latestVersion").get("id").asLong();
        handoffId = send(post("/api/v1/care-subjects/" + subjectId + "/handoffs"), ownerToken,
                Map.of("reason", "Consulta", "healthEntryIds", List.of(entry.get("id").asLong())), 201)
                .get("id").asLong();
        JsonNode created = createSession(true);
        sessionId = created.get("id").asLong();
        token = created.get("token").asText();
        assertTrue(created.get("accessCodeRequired").asBoolean());
        assertFalse(created.toString().contains("secret"));
    }

    @Test
    void displaysRotatingCodeWithServerClockAndNoSecretOrCache() throws Exception {
        JsonNode first = currentCode();
        assertTrue(first.get("code").asText().matches("[0-9]{6}"));
        assertFalse(first.get("consumed").asBoolean());
        assertEquals(30, first.get("periodSeconds").asInt());
        assertEquals(START.toString(), first.get("serverTime").asText());
        assertEquals(START.plusSeconds(30).toString(), first.get("expiresAt").asText());
        now.set(START.plusSeconds(29).plusNanos(999999999));
        assertEquals(first.get("code").asText(), currentCode().get("code").asText());
        assertEquals(32, codeStates.findById(sessionId).orElseThrow().getSecret().length);
        assertTrue(send(get("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken, null, 200)
                .get(0).get("accessCodeRequired").asBoolean());
        now.set(START.plusSeconds(30));
        JsonNode rotated = currentCode();
        assertEquals(START.plusSeconds(60).toString(), rotated.get("expiresAt").asText());
        assertFalse(rotated.toString().contains("secret"));
    }

    @Test
    void reportsCodeRequirementWithoutOpeningOrReturningHealthData() throws Exception {
        String endpoint = "/api/v1/public/handoff-sessions/";
        mvc.perform(get(endpoint + token + "/access-requirements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("{\"accessCodeRequired\":true}"));
        assertNull(sessions.findById(sessionId).orElseThrow().getFirstOpenedAt());

        String legacyToken = createSession(false).get("token").asText();
        mvc.perform(get(endpoint + legacyToken + "/access-requirements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessCodeRequired").value(false));
        mvc.perform(get(endpoint + "unknown/access-requirements")).andExpect(status().isNotFound());
        now.set(START.plusSeconds(3600 * 3));
        mvc.perform(get(endpoint + token + "/access-requirements")).andExpect(status().isGone());
    }

    @Test
    void verifiesCodeIssuesOpaqueSessionGrantAndPreventsFirstOpenedBypass() throws Exception {
        JsonNode required = open(null, 403);
        assertEquals("HANDOFF_CODE_REQUIRED", required.get("code").asText());
        assertFalse(required.has("items"));
        assertNull(sessions.findById(sessionId).orElseThrow().getFirstOpenedAt());
        send(get(sourceUrl(token)), null, null, 403);
        String code = currentCode().get("code").asText();
        JsonNode shared = open(code, 200);
        String grant = shared.get("accessGrant").asText();
        assertEquals(32, Base64.getUrlDecoder().decode(grant).length);
        assertEquals(START.plusSeconds(15 * 60).toString(), shared.get("accessExpiresAt").asText());
        assertEquals(START.toString(), shared.get("serverTime").asText());
        assertEquals(START, sessions.findById(sessionId).orElseThrow().getFirstOpenedAt());
        assertTrue(grants.existsBySessionIdAndTokenHashAndExpiresAtAfter(sessionId, TokenHasher.sha256(grant), START));
        assertEquals("HANDOFF_ACCESS_GRANT_REQUIRED", send(get(sourceUrl(token)), null, null, 403).get("code").asText());
        assertEquals("HANDOFF_ACCESS_GRANT_INVALID", send(get(sourceUrl(token)).header("X-Handoff-Access", token),
                null, null, 403).get("code").asText());
        when(fileStorage.load(storageKey)).thenReturn(PDF);
        mvc.perform(get(sourceUrl(token)).header("X-Handoff-Access", grant))
                .andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(sourceUrl(token)).header("X-Handoff-Access", grant).header("Range", "bytes=0-7"))
                .andExpect(status().isPartialContent());
        now.set(START.plusSeconds(31));
        // La rotación del código no interrumpe el permiso de lectura ya obtenido.
        mvc.perform(get(sourceUrl(token)).header("X-Handoff-Access", grant)).andExpect(status().isOk());

        JsonNode second = createSession(true);
        long secondId = second.get("id").asLong();
        String secondToken = second.get("token").asText();
        String secondCode = send(get(codeUrl(secondId)), ownerToken, null, 200).get("code").asText();
        send(post(openUrl(secondToken)), null, visitorBody(secondCode), 200);
        send(get(sourceUrl(secondToken)).header("X-Handoff-Access", grant), null, null, 403);
    }

    @Test
    void acceptedStepCannotBeReplayedAndNextStepWorksWithoutExtendingReadingWindow() throws Exception {
        String code = currentCode().get("code").asText();
        JsonNode first = open(code, 200);
        assertEquals("HANDOFF_CODE_USED", open(code, 403).get("code").asText());
        JsonNode consumed = currentCode();
        assertTrue(consumed.get("consumed").asBoolean());
        assertTrue(consumed.get("code").isNull());
        assertEquals(0, codeStates.findById(sessionId).orElseThrow().getFailedAttempts());
        now.set(START.plusSeconds(30));
        JsonNode next = currentCode();
        assertFalse(next.get("consumed").asBoolean());
        JsonNode second = open(next.get("code").asText(), 200);
        assertEquals(first.get("accessExpiresAt").asText(), second.get("accessExpiresAt").asText());
        assertNotEquals(first.get("accessGrant").asText(), second.get("accessGrant").asText());
        assertEquals(2, grants.countBySessionId(sessionId));
        now.set(START);
        assertTrue(currentCode().get("consumed").asBoolean());
        assertEquals("HANDOFF_CODE_USED", open(code, 403).get("code").asText());
        assertEquals(2, grants.countBySessionId(sessionId));
    }

    @Test
    void unexpectedFailureRollsBackCodeConsumptionGrantAndFirstOpeningTogether() throws Exception {
        String code = currentCode().get("code").asText();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            handoffService.open(token, "Visitante", "Médica", code);
            throw new IllegalStateException("Fallo inesperado antes de confirmar la apertura");
        }));
        assertNull(codeStates.findById(sessionId).orElseThrow().getLastAcceptedStep());
        assertNull(sessions.findById(sessionId).orElseThrow().getFirstOpenedAt());
        assertEquals(0, grants.countBySessionId(sessionId));
        open(code, 200);
    }

    @Test
    void invalidAttemptsPersistAcrossRollbackAndBlockEvenCorrectCodeUntilFiveMinutes() throws Exception {
        String actual = currentCode().get("code").asText();
        String wrong = actual.equals("000000") ? "000001" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals("HANDOFF_CODE_INVALID", open(wrong, 403).get("code").asText());
            assertEquals(attempt, codeStates.findById(sessionId).orElseThrow().getFailedAttempts());
        }
        assertEquals("HANDOFF_CODE_RATE_LIMITED", open(actual, 429).get("code").asText());
        assertNull(sessions.findById(sessionId).orElseThrow().getFirstOpenedAt());
        assertEquals(0, grants.countBySessionId(sessionId));
        now.set(START.plusSeconds(299));
        open(currentCode().get("code").asText(), 429);
        now.set(START.plusSeconds(300));
        open(currentCode().get("code").asText(), 200);
        assertEquals(0, codeStates.findById(sessionId).orElseThrow().getFailedAttempts());
    }

    @Test
    void concurrentCorrectRequestsConsumeOnlyOnceAndConcurrentFailuresNeverLoseCounts() throws Exception {
        String code = currentCode().get("code").asText();
        List<String> goodResults = concurrentOpens(token, code);
        assertEquals(1, goodResults.stream().filter("OK"::equals).count());
        assertEquals(7, goodResults.stream().filter("HANDOFF_CODE_USED"::equals).count());
        assertEquals(1, grants.countBySessionId(sessionId));
        JsonNode second = createSession(true);
        String secondCode = send(get(codeUrl(second.get("id").asLong())), ownerToken, null, 200).get("code").asText();
        String bad = secondCode.equals("000000") ? "000001" : "000000";
        List<String> badResults = concurrentOpens(second.get("token").asText(), bad);
        assertEquals(5, badResults.stream().filter("HANDOFF_CODE_INVALID"::equals).count());
        assertEquals(3, badResults.stream().filter("HANDOFF_CODE_RATE_LIMITED"::equals).count());
        assertEquals(5, codeStates.findById(second.get("id").asLong()).orElseThrow().getFailedAttempts());
    }

    @Test
    void revocationAbsoluteExpirationAndReadingWindowInvalidateGrantAndCode() throws Exception {
        String grant = open(currentCode().get("code").asText(), 200).get("accessGrant").asText();
        now.set(START.plusSeconds(900));
        send(get(codeUrl(sessionId)), ownerToken, null, 410);
        send(get(sourceUrl(token)).header("X-Handoff-Access", grant), null, null, 410);
        open("000000", 410);
        now.set(START);
        send(delete("/api/v1/handoff-sessions/" + sessionId), ownerToken, null, 204);
        send(get(codeUrl(sessionId)), ownerToken, null, 410);
        send(get(sourceUrl(token)).header("X-Handoff-Access", grant), null, null, 410);
        JsonNode unOpened = createSession(true);
        now.set(START.plusSeconds(3600));
        send(get(codeUrl(unOpened.get("id").asLong())), ownerToken, null, 410);
        send(post(openUrl(unOpened.get("token").asText())), null, visitorBody("000000"), 410);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void onlyActiveCreatorOrPrincipalCanSeeCodeAndRevokedCreatorCannotManageSessions() throws Exception {
        send(get(codeUrl(sessionId)), null, null, 401);
        String email = "other-code-" + UUID.randomUUID() + "@yuyay.app";
        String otherToken = register(email);
        send(get(codeUrl(sessionId)), otherToken, null, 403);
        CareRelationship relationship = relationships.saveAndFlush(CareRelationship.builder()
                .user(users.findByEmailIgnoreCase(email).orElseThrow()).careSubject(subjects.findById(subjectId).orElseThrow())
                .role(CareRole.CAREGIVER).status(CareStatus.ACTIVE).build());
        send(get(codeUrl(sessionId)), otherToken, null, 403);
        relationship.setRole(CareRole.PRINCIPAL);
        relationships.saveAndFlush(relationship);
        send(get(codeUrl(sessionId)), otherToken, null, 200);
        CareRelationship owner = relationships.findByUserIdAndCareSubjectId(ownerId, subjectId).orElseThrow();
        owner.setStatus(CareStatus.REVOKED);
        relationships.saveAndFlush(owner);
        send(get(codeUrl(sessionId)), ownerToken, null, 403);
        send(post("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken,
                Map.of("expiresInHours", 1, "sessionWindowMinutes", 15, "requireAccessCode", true), 403);
        send(get("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken, null, 403);
        send(delete("/api/v1/handoff-sessions/" + sessionId), ownerToken, null, 403);
    }

    @Test
    void legacySessionsWithoutOptInKeepExistingOpenAndSourceContract() throws Exception {
        JsonNode legacy = send(post("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken,
                Map.of("expiresInHours", 1, "sessionWindowMinutes", 15), 201);
        assertEquals(START.plus(1, ChronoUnit.HOURS).toString(), legacy.get("expiresAt").asText());
        assertFalse(legacy.get("accessCodeRequired").asBoolean());
        long legacyId = legacy.get("id").asLong();
        assertFalse(codeStates.existsById(legacyId));
        send(get(codeUrl(legacyId)), ownerToken, null, 409);
        JsonNode shared = send(post(openUrl(legacy.get("token").asText())), null, visitorBody(null), 200);
        assertTrue(shared.get("accessGrant").isNull());
        when(fileStorage.load(storageKey)).thenReturn(PDF);
        mvc.perform(get(sourceUrl(legacy.get("token").asText()))).andExpect(status().isOk());
    }

    @Test
    void minuteDurationsKeepTheAbsoluteDeadlineOnLateAndRepeatedOpens() throws Exception {
        JsonNode created = send(post("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken,
                Map.of("expiresInMinutes", 30, "sessionWindowMinutes", 25, "requireAccessCode", true), 201);
        Instant deadline = START.plus(30, ChronoUnit.MINUTES);
        assertEquals(deadline.toString(), created.get("expiresAt").asText());
        assertEquals(25, created.get("sessionWindowMinutes").asInt());
        long id = created.get("id").asLong();
        String rawToken = created.get("token").asText();

        Instant firstOpen = START.plus(10, ChronoUnit.MINUTES);
        now.set(firstOpen);
        for (int opening = 0; opening < 2; opening++) {
            String code = send(get(codeUrl(id)), ownerToken, null, 200).get("code").asText();
            JsonNode shared = send(post(openUrl(rawToken)), null, visitorBody(code), 200);
            assertEquals(deadline.toString(), shared.get("accessExpiresAt").asText());
            assertEquals(firstOpen, sessions.findById(id).orElseThrow().getFirstOpenedAt());
            now.updateAndGet(time -> time.plusSeconds(30));
        }

        now.set(deadline);
        send(get(codeUrl(id)), ownerToken, null, 410);
        send(post(openUrl(rawToken)), null, visitorBody(null), 410);
    }

    @Test
    void acceptsInclusiveMinuteLimitsAndRejectsInvalidDurations() throws Exception {
        String path = "/api/v1/handoffs/" + handoffId + "/sessions";
        for (int minutes : List.of(1, 4320)) {
            JsonNode created = send(post(path), ownerToken,
                    Map.of("expiresInMinutes", minutes, "sessionWindowMinutes", minutes), 201);
            assertEquals(START.plus(minutes, ChronoUnit.MINUTES).toString(), created.get("expiresAt").asText());
        }

        for (String invalid : List.of(
                "{\"sessionWindowMinutes\":25}",
                "{\"expiresInHours\":null,\"expiresInMinutes\":null,\"sessionWindowMinutes\":25}",
                "{\"expiresInHours\":73,\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":0,\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":-1,\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":4321,\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":2147483648,\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":30.5,\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":\"30\",\"sessionWindowMinutes\":25}",
                "{\"expiresInMinutes\":30}",
                "{\"expiresInMinutes\":30,\"sessionWindowMinutes\":0}",
                "{\"expiresInMinutes\":30,\"sessionWindowMinutes\":25.5}",
                "{\"expiresInMinutes\":4320,\"sessionWindowMinutes\":4321}")) {
            mvc.perform(post(path).header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(post(path).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInHours\":1,\"sessionWindowMinutes\":61}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("sessionWindowMinutes")));
        mvc.perform(post(path).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInHours\":1,\"expiresInMinutes\":30,\"sessionWindowMinutes\":25}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("expiresInMinutes")));
    }

    private List<String> concurrentOpens(String rawToken, String code) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<String>> tasks = IntStream.range(0, 8).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("No empezó la concurrencia");
                    handoffService.open(rawToken, "Visitante", "Médica", code);
                    return "OK";
                } catch (HandoffCodeException exception) {
                    return exception.getCode();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }, executor)).toList();
            start.countDown();
            return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> tasks.stream().map(CompletableFuture::join).toList()).get(30, TimeUnit.SECONDS);
        }
    }

    private JsonNode createSession(boolean protectedSession) throws Exception {
        return send(post("/api/v1/handoffs/" + handoffId + "/sessions"), ownerToken,
                Map.of("expiresInHours", 1, "sessionWindowMinutes", 15, "requireAccessCode", protectedSession), 201);
    }

    private JsonNode currentCode() throws Exception {
        String response = mvc.perform(get(codeUrl(sessionId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private JsonNode open(String code, int expected) throws Exception {
        return send(post(openUrl(token)), null, visitorBody(code), expected);
    }

    private Map<String, Object> visitorBody(String code) {
        var body = new java.util.HashMap<String, Object>();
        body.put("visitorName", "Visitante");
        body.put("visitorRole", "Médica");
        if (code != null) body.put("accessCode", code);
        return body;
    }

    private String codeUrl(long id) { return "/api/v1/handoff-sessions/" + id + "/access-code"; }
    private String openUrl(String rawToken) { return "/api/v1/public/handoff-sessions/" + rawToken + "/open"; }
    private String sourceUrl(String rawToken) {
        return "/api/v1/public/handoff-sessions/" + rawToken + "/versions/" + versionId + "/source/content";
    }

    private String register(String email) throws Exception {
        return send(post("/api/v1/auth/register"), null, Map.of("email", email, "name", "Cuidadora",
                "password", "Secreta123", "termsAccepted", true, "termsVersion", "1.0"), 201).get("accessToken").asText();
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String authToken, Object body, int expected) throws Exception {
        if (authToken != null) request.header("Authorization", "Bearer " + authToken);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isEmpty() ? null : json.readTree(response);
    }
}
