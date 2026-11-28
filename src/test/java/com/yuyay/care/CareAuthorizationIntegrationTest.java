package com.yuyay.care;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CareAuthorizationIntegrationTest {
    private static final String BASE = "/api/v1/care-subjects";
    private static final String TABLE = "care_authorization_declarations";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CareSubjectRepository subjects;
    @Autowired CareRelationshipRepository relationships;
    @Autowired UserRepository users;

    private Session owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
    }

    @Test
    void policyRequiresLoginAndDescribesDeclarationsWithoutVerification() throws Exception {
        mvc.perform(get(BASE + "/authorization-policy")).andExpect(status().isUnauthorized());
        JsonNode policy = send(get(BASE + "/authorization-policy"), owner, null, 200);
        assertEquals("1", policy.get("textVersion").asText());
        assertEquals(5, policy.get("options").size());
        assertEquals("¿Cómo estás autorizado para usar sus datos en Yuyay?", policy.get("thirdPartyQuestion").asText());
        for (JsonNode option : policy.get("options")) {
            assertFalse(option.get("type").asText().contains("VERIFIED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELF", "FICTITIOUS", "HOLDER_PERMISSION", "LEGAL_REPRESENTATION"})
    void creationPersistsActorAndExactServerTextAtomically(String type) throws Exception {
        Instant before = Instant.now();
        long id = create(type);
        JsonNode events = history(id, owner);
        assertEquals(1, events.size());
        JsonNode event = events.get(0);
        assertEquals(id, event.get("careSubjectId").asLong());
        assertEquals(owner.id(), event.get("declaredByUserId").asLong());
        assertEquals(type, event.get("type").asText());
        assertEquals("1", event.get("textVersion").asText());
        assertFalse(Instant.parse(event.get("declaredAt").asText()).isBefore(before));
        JsonNode policy = send(get(BASE + "/authorization-policy"), owner, null, 200);
        JsonNode option = null;
        for (JsonNode candidate : policy.get("options")) {
            if (type.equals(candidate.get("type").asText())) option = candidate;
        }
        assertNotNull(option);
        String expected = String.join("\n", option.get("label").asText(),
                option.get("description").asText(), option.get("confirmationText").asText());
        if (type.equals("HOLDER_PERMISSION") || type.equals("LEGAL_REPRESENTATION")) {
            expected = policy.get("thirdPartyQuestion").asText() + "\n" + expected;
        }
        assertEquals(expected, event.get("text").asText());
        assertEquals(option.get("scope").asText(), event.get("scope").asText());
        assertEquals(expected, jdbc.queryForObject("select declaration_text from " + TABLE + " where id = ?",
                String.class, event.get("id").asLong()));
    }

    @Test
    void missingDeclarationRejectsOldCreateWithoutWritingSubject() throws Exception {
        long before = subjects.count();
        JsonNode error = send(post(BASE), owner, Map.of("name", "Prueba"), 400);
        assertEquals("Actualiza Yuyay para registrar la declaración de esta persona", error.get("message").asText());
        assertEquals(before, subjects.count());
    }

    @Test
    void unconfirmedMissingTypeAndUnknownTypeDoNotCreateAnything() throws Exception {
        long before = subjects.count();
        send(post(BASE), owner, Map.of("requestId", UUID.randomUUID().toString(), "name", "Prueba", "declaration",
                Map.of("type", "FICTITIOUS", "textVersion", "1", "confirmed", false)), 400);
        send(post(BASE), owner, Map.of("requestId", UUID.randomUUID().toString(), "name", "Prueba", "declaration",
                Map.of("textVersion", "1", "confirmed", true)), 400);
        send(post(BASE), owner, Map.of("requestId", UUID.randomUUID().toString(), "name", "Prueba", "declaration",
                Map.of("type", "VERIFIED", "textVersion", "1", "confirmed", true)), 400);
        assertEquals(before, subjects.count());
    }

    @Test
    void pendingAndStaleVersionCannotCreateSubjectOrHealthContainer() throws Exception {
        long before = subjects.count();
        send(post(BASE), owner, Map.of("requestId", UUID.randomUUID().toString(), "name", "Prueba", "declaration", declaration("PENDING")), 409);
        send(post(BASE), owner, Map.of("requestId", UUID.randomUUID().toString(), "name", "Prueba", "declaration",
                Map.of("type", "FICTITIOUS", "textVersion", "obsolete", "confirmed", true)), 409);
        assertEquals(before, subjects.count());
    }

    @Test
    void legacyProfileStaysUndeclaredUntilPrincipalExplicitlyRecordsIt() throws Exception {
        long id = legacySubject();
        assertEquals(0, history(id, owner).size());
        send(get(BASE + "/" + id), owner, null, 200);
        send(patch(BASE + "/" + id), owner, Map.of("name", "Nombre actualizado"), 200);
        assertEquals(0, history(id, owner).size());
        send(post(eventsUrl(id)), owner, declaration("HOLDER_PERMISSION"), 200);
        assertEquals(1, history(id, owner).size());
    }

    @Test
    void repeatReturnsSameEventAndChangeKeepsHistory() throws Exception {
        long id = create("FICTITIOUS");
        JsonNode original = history(id, owner).get(0);
        JsonNode replay = send(post(eventsUrl(id)), owner, declaration("FICTITIOUS"), 200);
        assertEquals(original.get("id"), replay.get("id"));
        JsonNode updated = send(post(eventsUrl(id)), owner,
                change("HOLDER_PERMISSION", original.get("id").asLong()), 200);
        assertNotEquals(original.get("id"), updated.get("id"));
        JsonNode events = history(id, owner);
        assertEquals(2, events.size());
        assertEquals(updated, events.get(0));
        assertEquals(original, events.get(1));
        send(post(eventsUrl(id)), owner, change("HOLDER_PERMISSION", original.get("id").asLong()), 200);
        send(post(eventsUrl(id)), owner, change("SELF", original.get("id").asLong()), 409);
        assertEquals(events, history(id, owner));
    }

    @Test
    void delayedOriginalReplayDoesNotUndoSubsequentChange() throws Exception {
        long id = legacySubject();
        JsonNode first = send(post(eventsUrl(id)), owner, declaration("FICTITIOUS"), 200);
        JsonNode second = send(post(eventsUrl(id)), owner,
                change("HOLDER_PERMISSION", first.get("id").asLong()), 200);
        send(post(eventsUrl(id)), owner, declaration("FICTITIOUS"), 409);
        assertEquals(second, history(id, owner).get(0));
        assertEquals(2, history(id, owner).size());
    }

    @Test
    void pendingOrInvalidUpdatePreservesExistingHistory() throws Exception {
        long id = create("FICTITIOUS");
        JsonNode before = history(id, owner);
        long previous = before.get(0).get("id").asLong();
        send(post(eventsUrl(id)), owner, change("PENDING", previous), 409);
        Map<String, Object> invalid = change("HOLDER_PERMISSION", previous);
        invalid.put("confirmed", false);
        send(post(eventsUrl(id)), owner, invalid, 400);
        invalid.put("confirmed", true);
        invalid.put("textVersion", "obsolete");
        send(post(eventsUrl(id)), owner, invalid, 409);
        assertEquals(before, history(id, owner));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAREGIVER", "PENDING", "REVOKED", "OUTSIDER", "ADMIN"})
    void onlyAnActivePrincipalCanReadAndWriteDeclarations(String scenario) throws Exception {
        long id = create("FICTITIOUS");
        Session other = register();
        switch (scenario) {
            case "CAREGIVER" -> link(other, id, CareRole.CAREGIVER, CareStatus.ACTIVE);
            case "PENDING" -> link(other, id, CareRole.PRINCIPAL, CareStatus.PENDING);
            case "REVOKED" -> link(other, id, CareRole.PRINCIPAL, CareStatus.REVOKED);
            case "ADMIN" -> jdbc.update("update users set role = 'ADMIN' where id = ?", other.id());
        }
        send(get(eventsUrl(id)), other, null, 403);
        send(post(eventsUrl(id)), other, declaration("HOLDER_PERMISSION"), 403);
        assertEquals(1, history(id, owner).size());
    }

    @Test
    void secondPrincipalRecordsTheirOwnIdentityInsteadOfReusingAnotherActorsReceipt() throws Exception {
        long id = create("FICTITIOUS");
        JsonNode first = history(id, owner).get(0);
        Session second = register();
        link(second, id, CareRole.PRINCIPAL, CareStatus.ACTIVE);
        JsonNode receipt = send(post(eventsUrl(id)), second,
                change("FICTITIOUS", first.get("id").asLong()), 200);
        assertEquals(second.id(), receipt.get("declaredByUserId").asLong());
        assertEquals(2, history(id, owner).size());
    }

    @Test
    void concurrentRetriesWriteOneDeclaration() throws Exception {
        long id = legacySubject();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return send(post(eventsUrl(id)), owner, declaration("FICTITIOUS"), 200); });
            var second = pool.submit(() -> { start.await(); return send(post(eventsUrl(id)), owner, declaration("FICTITIOUS"), 200); });
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS).get("id"), second.get(15, TimeUnit.SECONDS).get("id"));
        }
        assertEquals(1, history(id, owner).size());
    }

    @Test
    void createRetriesReuseProfileAndRejectDifferentContent() throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("requestId", UUID.randomUUID().toString(),
                "name", "Perfil de prueba", "declaration", declaration("FICTITIOUS")));
        long before = subjects.count();
        JsonNode first = send(post(BASE), owner, request, 201);
        JsonNode retry = send(post(BASE), owner, request, 201);
        assertEquals(first.get("id"), retry.get("id"));
        assertEquals(before + 1, subjects.count());
        assertEquals(1, history(first.get("id").asLong(), owner).size());
        request.put("notes", "Otro contenido");
        send(post(BASE), owner, request, 409);
        assertEquals(before + 1, subjects.count());
    }

    @Test
    void creationKeysAreScopedToAccountAndReplayChecksCurrentPermissions() throws Exception {
        Map<String, Object> request = Map.of("requestId", UUID.randomUUID().toString(),
                "name", "Perfil de prueba", "declaration", declaration("FICTITIOUS"));
        long first = send(post(BASE), owner, request, 201).get("id").asLong();
        Session other = register();
        long second = send(post(BASE), other, request, 201).get("id").asLong();
        assertNotEquals(first, second);
        jdbc.update("update care_relationships set status = 'REVOKED' where care_subject_id = ? and user_id = ?",
                first, owner.id());
        send(post(BASE), owner, request, 403);
    }

    @Test
    void concurrentCreateRetriesWriteOneProfile() throws Exception {
        Map<String, Object> request = Map.of("requestId", UUID.randomUUID().toString(),
                "name", "Perfil de prueba", "declaration", declaration("FICTITIOUS"));
        long before = subjects.count();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return send(post(BASE), owner, request, 201); });
            var second = pool.submit(() -> { start.await(); return send(post(BASE), owner, request, 201); });
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS).get("id"), second.get(15, TimeUnit.SECONDS).get("id"));
        }
        assertEquals(before + 1, subjects.count());
    }

    @Test
    void concurrentDifferentDeclarationsDoNotOverwriteEachOther() throws Exception {
        long id = create("FICTITIOUS");
        long previous = history(id, owner).get(0).get("id").asLong();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return appendStatus(id, change("HOLDER_PERMISSION", previous)); });
            var second = pool.submit(() -> { start.await(); return appendStatus(id, change("LEGAL_REPRESENTATION", previous)); });
            start.countDown();
            var outcomes = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertTrue(outcomes.contains(200), outcomes.toString());
            assertTrue(outcomes.contains(409), outcomes.toString());
        }
        assertEquals(2, history(id, owner).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad/key", "bad@key", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void invalidCreationKeyDoesNotWrite(String key) throws Exception {
        long before = subjects.count();
        send(post(BASE), owner, Map.of("requestId", key, "name", "Prueba", "declaration", declaration("FICTITIOUS")), 400);
        assertEquals(before, subjects.count());
    }

    @Test
    void deletingAnEmptyProfileStillWorksWithDeclarationHistory() throws Exception {
        long id = create("FICTITIOUS");
        send(delete(BASE + "/" + id), owner, null, 204);
        assertFalse(subjects.existsById(id));
        assertEquals(0, jdbc.queryForObject("select count(*) from " + TABLE + " where care_subject_id = ?", Integer.class, id));
    }

    private record Session(String token, long id) {}

    private Session register() throws Exception {
        JsonNode auth = send(post("/api/v1/auth/register"), null, Map.of("email",
                "declaration." + UUID.randomUUID() + "@yuyay.app", "password", "Secreta123", "name", "Prueba",
                "termsAccepted", true, "termsVersion", "1.3", "dni", "12345678", "fullName", "Persona Sintetica"), 201);
        return new Session(auth.get("accessToken").asText(), auth.get("user").get("id").asLong());
    }

    private long create(String type) throws Exception {
        return send(post(BASE), owner, Map.of("requestId", UUID.randomUUID().toString(), "name", "Persona de prueba", "declaration", declaration(type)), 201).get("id").asLong();
    }

    private long legacySubject() {
        long id = subjects.save(CareSubject.builder().name("Perfil anterior").build()).getId();
        link(owner, id, CareRole.PRINCIPAL, CareStatus.ACTIVE);
        return id;
    }

    private void link(Session actor, long id, CareRole role, CareStatus status) {
        relationships.save(CareRelationship.builder().user(users.getReferenceById(actor.id()))
                .careSubject(subjects.getReferenceById(id)).role(role).status(status).build());
    }

    private Map<String, Object> declaration(String type) {
        return Map.of("type", type, "textVersion", "1", "confirmed", true);
    }

    private Map<String, Object> change(String type, long previous) {
        Map<String, Object> request = new HashMap<>(declaration(type));
        request.put("previousDeclarationId", previous);
        return request;
    }

    private String eventsUrl(long id) { return BASE + "/" + id + "/authorization-declarations"; }

    private int appendStatus(long id, Object body) throws Exception {
        return mvc.perform(post(eventsUrl(id)).header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode history(long id, Session actor) throws Exception {
        return send(get(eventsUrl(id)), actor, null, 200);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, Session actor, Object body, int expected) throws Exception {
        if (actor != null) req.header("Authorization", "Bearer " + actor.token());
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String result = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return result.isBlank() ? null : json.readTree(result);
    }
}
