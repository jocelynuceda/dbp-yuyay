package com.yuyay.consultation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consultas médicas: CRUD del PRINCIPAL, fechas pasadas, presentes y futuras, permisos por rol
 * y /changes con los registros de salud declarados desde la consulta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationPreparationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String anaToken;
    private String brunoToken;
    private String strangerToken;
    private long subjectId;
    private String consultations;

    @BeforeEach
    void setUp() throws Exception {
        String brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        anaToken = register("ana." + UUID.randomUUID() + "@yuyay.app", "Ana");
        brunoToken = register(brunoEmail, "Bruno");
        strangerToken = register("carla." + UUID.randomUUID() + "@yuyay.app", "Carla");

        subjectId = send(post("/api/v1/care-subjects"), anaToken,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        long relationshipId = send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\"}", 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + relationshipId + "/accept"), brunoToken, "{}", 200);
        consultations = "/api/v1/care-subjects/" + subjectId + "/consultations";
    }


    private long appointment() throws Exception {
        return send(post(consultations), anaToken, consultation(LocalDate.now().plusDays(1), "Control ficticio"), 201).get("id").asLong();
    }

    private JsonNode entry(String title) throws Exception {
        return send(post("/api/v1/care-subjects/" + subjectId + "/health-entries"), anaToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"" + title + "\",\"confidenceLevel\":\"CONFIRMED\"}", 201);
    }

    private JsonNode preparation(long id, String token) throws Exception {
        return send(get("/api/v1/consultations/" + id + "/preparation"), token, null, 200);
    }

    private String confirmation(long id, String key, java.util.List<JsonNode> entries) throws Exception {
        var selected = entries.stream().map(e -> java.util.Map.of(
                "entryId", e.get("id").asLong(), "versionId", e.get("latestVersion").get("id").asLong())).toList();
        return json.writeValueAsString(java.util.Map.of("requestId", key, "preparationToken",
                preparation(id, anaToken).get("preparationToken").asText(),
                "reason", "Motivo revisado ficticio", "questions", "Pregunta ficticia", "entries", selected));
    }

    private String confirmUrl(long id) { return "/api/v1/consultations/" + id + "/preparation/confirm"; }

    @Test
    void preparationEmptyAuthorizedAndReadOnly() throws Exception {
        long id = appointment();
        assertEquals(0, preparation(id, brunoToken).get("categories").size());
        assertEquals(0, send(get("/api/v1/care-subjects/" + subjectId + "/handoffs"), anaToken, null, 200).size());
        send(get("/api/v1/consultations/" + id + "/preparation"), strangerToken, null, 403);
        send(get("/api/v1/consultations/99999999/preparation"), anaToken, null, 404);
        String body = confirmation(id, UUID.randomUUID().toString(), java.util.List.of());
        assertEquals(0, send(post(confirmUrl(id)), anaToken, body, 200).get("items").size());
    }

    @Test
    void preparationContainsExactVersionInStableOrder() throws Exception {
        long id = appointment();
        JsonNode first = entry("Ficticio A");
        JsonNode second = entry("Ficticio B");
        JsonNode prepared = preparation(id, anaToken);
        assertEquals(prepared, preparation(id, anaToken));
        var items = prepared.get("categories").get(0).get("entries");
        assertEquals(first.get("id"), items.get(0).get("entryId"));
        assertEquals(second.get("latestVersion").get("id"), items.get(1).get("versionId"));
        assertEquals("Ficticio A", items.get(0).get("version").get("title").asText());
    }

    @Test
    void preservesOrderRetriesAndFrozenPublicContent() throws Exception {
        long id = appointment();
        JsonNode first = entry("Ficticio A");
        JsonNode second = entry("Ficticio B");
        String body = confirmation(id, UUID.randomUUID().toString(), java.util.List.of(second, first));
        JsonNode saved = send(post(confirmUrl(id)), brunoToken, body, 200);
        assertEquals(second.get("id"), saved.get("items").get(0).get("healthEntryId"));
        send(patch("/api/v1/care-subjects/" + subjectId + "/health-entries/" + first.get("id").asLong()), anaToken,
                "{\"title\":\"Cambio ficticio\",\"confidenceLevel\":\"CONFIRMED\"}", 200);
        JsonNode retry = send(post(confirmUrl(id)), brunoToken, body, 200);
        assertEquals(saved, retry);
        JsonNode reopened = send(get("/api/v1/handoffs/" + saved.get("id").asLong()), anaToken, null, 200);
        assertEquals(saved.get("items"), reopened.get("items"));
        assertEquals("Ficticio A", reopened.get("items").get(1).get("title").asText());
        send(post(confirmUrl(id)), brunoToken, body.replace("Motivo revisado ficticio", "Otro motivo"), 409);
        send(post(confirmUrl(id)), strangerToken, body, 403);
        assertEquals(0, send(get("/api/v1/handoffs/" + saved.get("id").asLong() + "/sessions"), brunoToken, null, 200).size());
        JsonNode link = send(post("/api/v1/handoffs/" + saved.get("id").asLong() + "/sessions"), brunoToken,
                "{\"expiresInHours\":1,\"sessionWindowMinutes\":15}", 201);
        JsonNode shared = send(post("/api/v1/public/handoff-sessions/" + link.get("token").asText() + "/open"), null,
                "{\"visitorName\":\"Medico ficticio\",\"visitorRole\":\"DOCTOR\"}", 200);
        assertEquals(saved.get("items"), shared.get("items"));

    }

    @Test
    void rejectsChangedVersionsDeletedEntriesAndChangedAppointment() throws Exception {
        long id = appointment();
        JsonNode first = entry("Ficticio A");
        String body = confirmation(id, UUID.randomUUID().toString(), java.util.List.of(first));
        send(patch("/api/v1/care-subjects/" + subjectId + "/health-entries/" + first.get("id").asLong()), anaToken,
                "{\"title\":\"Cambio ficticio\",\"confidenceLevel\":\"CONFIRMED\"}", 200);
        send(post(confirmUrl(id)), anaToken, body, 409);
        JsonNode current = send(get("/api/v1/care-subjects/" + subjectId + "/health-entries/" + first.get("id").asLong()), anaToken, null, 200);
        String changedAppointment = confirmation(id, UUID.randomUUID().toString(), java.util.List.of(current));
        send(patch("/api/v1/consultations/" + id), anaToken, "{\"reason\":\"Otro control\"}", 200);
        send(post(confirmUrl(id)), anaToken, changedAppointment, 409);
        String deleted = confirmation(id, UUID.randomUUID().toString(), java.util.List.of(current));
        send(delete("/api/v1/care-subjects/" + subjectId + "/health-entries/" + first.get("id").asLong()), anaToken, null, 204);
        send(post(confirmUrl(id)), anaToken, deleted, 409);
    }

    @Test
    void rejectsDuplicatesAndEntriesFromAnotherPerson() throws Exception {
        long id = appointment();
        JsonNode first = entry("Ficticio A");
        send(post(confirmUrl(id)), anaToken, confirmation(id, UUID.randomUUID().toString(), java.util.List.of(first, first)), 400);
        long other = send(post("/api/v1/care-subjects"), anaToken,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Otro ficticio\"}", 201).get("id").asLong();
        JsonNode foreign = send(post("/api/v1/care-subjects/" + other + "/health-entries"), anaToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Ficticio ajeno\",\"confidenceLevel\":\"CONFIRMED\"}", 201);
        send(post(confirmUrl(id)), anaToken, confirmation(id, UUID.randomUUID().toString(), java.util.List.of(foreign)), 400);
    }

    @Test
    void concurrentRetriesCreateOnlyOneHandoff() throws Exception {
        long id = appointment();
        String body = confirmation(id, UUID.randomUUID().toString(), java.util.List.of(entry("Ficticio")));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var calls = java.util.stream.IntStream.range(0, 5).mapToObj(i -> executor.submit(() -> {
                start.await();
                return send(post(confirmUrl(id)), anaToken, body, 200).get("id").asLong();
            })).toList();
            start.countDown();
            var ids = new java.util.HashSet<Long>();
            for (var call : calls) ids.add(call.get(45, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, ids.size());
        }
        assertEquals(1, send(get("/api/v1/care-subjects/" + subjectId + "/handoffs"), anaToken, null, 200).size());
    }

    @Test
    void revokedCaregiverCannotReplayOrPrepare() throws Exception {
        long id = appointment();
        String body = confirmation(id, UUID.randomUUID().toString(), java.util.List.of());
        send(post(confirmUrl(id)), brunoToken, body, 200);
        JsonNode relationships = send(get("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken, null, 200);
        for (JsonNode relationship : relationships) {
            if ("CAREGIVER".equals(relationship.get("role").asText())) {
                send(delete("/api/v1/care-subjects/" + subjectId + "/relationships/" + relationship.get("id").asLong()),
                        anaToken, null, 204);
            }
        }
        send(post(confirmUrl(id)), brunoToken, body, 403);
        send(get("/api/v1/consultations/" + id + "/preparation"), brunoToken, null, 403);
    }

    @Test
    void manualCreationStillWorks() throws Exception {
        JsonNode first = entry("Manual ficticio");
        JsonNode result = send(post("/api/v1/care-subjects/" + subjectId + "/handoffs"), anaToken,
                "{\"reason\":\"Manual\",\"healthEntryIds\":[" + first.get("id").asLong() + "]}", 201);
        assertEquals(first.get("latestVersion").get("id"), result.get("items").get(0).get("healthEntryVersionId"));
    }

    private String consultation(LocalDate date, String reason) {
        return "{\"date\":\"" + date + "\",\"reason\":\"" + reason + "\"}";
    }

    private String register(String email, String name) throws Exception {
        return send(post("/api/v1/auth/register"), null,
                "{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.2\",\"dni\":\"12345678\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201).get("accessToken").asText();
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
