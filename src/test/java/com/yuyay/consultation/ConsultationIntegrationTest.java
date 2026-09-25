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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consultas médicas: CRUD del PRINCIPAL, fecha no futura, permisos por rol
 * y /changes con los registros de salud declarados desde la consulta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationIntegrationTest {

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
                "{\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        long relationshipId = send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\"}", 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + relationshipId + "/accept"), brunoToken, "{}", 200);
        consultations = "/api/v1/care-subjects/" + subjectId + "/consultations";
    }

    @Test
    void principalCanCreateReadUpdateAndDelete() throws Exception {
        long id = send(post(consultations), anaToken, consultation(LocalDate.now().minusDays(3), "Control de presión"), 201)
                .get("id").asLong();

        assertEquals(1, send(get(consultations), anaToken, null, 200).size());
        assertEquals("Pedro", send(get("/api/v1/consultations/" + id), anaToken, null, 200).get("careSubjectName").asText());

        JsonNode updated = send(patch("/api/v1/consultations/" + id), anaToken, "{\"reason\":\"Control mensual\"}", 200);
        assertEquals("Control mensual", updated.get("reason").asText());

        send(delete("/api/v1/consultations/" + id), anaToken, null, 204);
        send(get("/api/v1/consultations/" + id), anaToken, null, 404);
    }

    @Test
    void futureDateOrMissingReasonReturns400() throws Exception {
        send(post(consultations), anaToken, consultation(LocalDate.now().plusDays(1), "Control"), 400);
        send(post(consultations), anaToken, consultation(LocalDate.now(), ""), 400);

        long id = send(post(consultations), anaToken, consultation(LocalDate.now(), "Control"), 201).get("id").asLong();
        send(patch("/api/v1/consultations/" + id), anaToken, "{\"date\":\"" + LocalDate.now().plusDays(1) + "\"}", 400);
    }

    @Test
    void caregiverCanReadButNotWriteAndStrangerIsForbidden() throws Exception {
        long id = send(post(consultations), anaToken, consultation(LocalDate.now(), "Control"), 201).get("id").asLong();

        send(get(consultations), brunoToken, null, 200);
        send(post(consultations), brunoToken, consultation(LocalDate.now(), "Control"), 403);
        send(patch("/api/v1/consultations/" + id), brunoToken, "{\"reason\":\"x\"}", 403);
        send(delete("/api/v1/consultations/" + id), brunoToken, null, 403);

        send(get(consultations), strangerToken, null, 403);
        send(get("/api/v1/consultations/" + id), strangerToken, null, 403);
    }

    @Test
    void changesListsHealthEntriesDeclaredSinceTheConsultation() throws Exception {
        long id = send(post(consultations), anaToken, consultation(LocalDate.now().minusDays(1), "Control"), 201)
                .get("id").asLong();
        send(post("/api/v1/care-subjects/" + subjectId + "/health-entries"), brunoToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"CONFIRMED\"}", 201);

        JsonNode changes = send(get(consultations + "/changes").param("sinceConsultationId", String.valueOf(id)),
                brunoToken, null, 200);
        assertEquals(1, changes.size());
        assertEquals("Penicilina", changes.get(0).get("title").asText());

        send(get(consultations + "/changes").param("sinceConsultationId", "999999"), anaToken, null, 404);
    }

    private String consultation(LocalDate date, String reason) {
        return "{\"date\":\"" + date + "\",\"reason\":\"" + reason + "\"}";
    }

    private String register(String email, String name) throws Exception {
        return send(post("/api/v1/auth/register"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201).get("accessToken").asText();
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
