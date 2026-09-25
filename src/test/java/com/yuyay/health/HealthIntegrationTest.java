package com.yuyay.health;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorrido básico de registros de salud: crear → versión 1, editar → versión 2
 * con quien la declaró, borrar (soft delete) sin perder el historial, validación
 * y acceso de terceros.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String anaToken;
    private String brunoToken;
    private String strangerToken;
    private String entries;

    @BeforeEach
    void setUp() throws Exception {
        String brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        anaToken = register("ana." + UUID.randomUUID() + "@yuyay.app", "Ana");
        brunoToken = register(brunoEmail, "Bruno");
        strangerToken = register("carla." + UUID.randomUUID() + "@yuyay.app", "Carla");

        long subjectId = send(post("/api/v1/care-subjects"), anaToken,
                "{\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        long relationshipId = send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\"}", 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + relationshipId + "/accept"), brunoToken, "{}", 200);
        entries = "/api/v1/care-subjects/" + subjectId + "/health-entries";
    }

    @Test
    void createThenEditKeepsEveryVersionWithItsAuthor() throws Exception {
        JsonNode created = send(post(entries), anaToken,
                "{\"categoryCode\":\"MEDICATION\",\"title\":\"Losartán\",\"dose\":\"50 mg\",\"confidenceLevel\":\"CONFIRMED\"}", 201);
        long entryId = created.get("id").asLong();
        assertEquals(1, created.get("latestVersion").get("versionNumber").asInt());
        assertEquals("CREATED", created.get("latestVersion").get("changeType").asText());

        JsonNode updated = send(patch(entries + "/" + entryId), brunoToken,
                "{\"title\":\"Losartán\",\"dose\":\"100 mg\",\"confidenceLevel\":\"CONFIRMED\"}", 200);
        assertEquals(2, updated.get("latestVersion").get("versionNumber").asInt());
        assertEquals("Bruno", updated.get("latestVersion").get("declaredBy").get("name").asText());

        JsonNode versions = send(get(entries + "/" + entryId + "/versions"), anaToken, null, 200);
        assertEquals(2, versions.size());
        assertEquals("100 mg", versions.get(0).get("dose").asText());
        assertEquals("50 mg", versions.get(1).get("dose").asText());
        assertEquals("Ana", versions.get(1).get("declaredBy").get("name").asText());
    }

    @Test
    void deletedEntryDisappearsButHistoryRemains() throws Exception {
        long entryId = send(post(entries), anaToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();

        send(delete(entries + "/" + entryId), anaToken, null, 204);

        assertEquals(0, send(get(entries), anaToken, null, 200).size());
        send(get(entries + "/" + entryId), anaToken, null, 404);
        JsonNode versions = send(get(entries + "/" + entryId + "/versions"), anaToken, null, 200);
        assertEquals(2, versions.size());
        assertEquals("DELETED", versions.get(0).get("changeType").asText());
    }

    @Test
    void listFiltersByCategory() throws Exception {
        send(post(entries), anaToken, "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"CONFIRMED\"}", 201);
        send(post(entries), anaToken, "{\"categoryCode\":\"CONDITION\",\"title\":\"Hipertensión\",\"confidenceLevel\":\"CONFIRMED\"}", 201);

        JsonNode allergies = send(get(entries).param("category", "ALLERGY"), anaToken, null, 200);
        assertEquals(1, allergies.size());
        assertEquals("ALLERGY", allergies.get(0).get("categoryCode").asText());
        assertEquals(2, send(get(entries), anaToken, null, 200).size());
    }

    @Test
    void invalidRequestsReturn400Or404() throws Exception {
        send(post(entries), anaToken, "{\"categoryCode\":\"ALLERGY\",\"title\":\"\",\"confidenceLevel\":\"CONFIRMED\"}", 400);
        send(post(entries), anaToken, "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\"}", 400);
        send(post(entries), anaToken, "{\"categoryCode\":\"SURGERY\",\"title\":\"x\",\"confidenceLevel\":\"CONFIRMED\"}", 404);
    }

    @Test
    void strangerCannotReadOrWrite() throws Exception {
        long entryId = send(post(entries), anaToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();

        send(get(entries), strangerToken, null, 403);
        send(get(entries + "/" + entryId), strangerToken, null, 403);
        send(post(entries), strangerToken, "{\"categoryCode\":\"ALLERGY\",\"title\":\"x\",\"confidenceLevel\":\"CONFIRMED\"}", 403);
        send(patch(entries + "/" + entryId), strangerToken, "{\"title\":\"x\",\"confidenceLevel\":\"CONFIRMED\"}", 403);
        send(delete(entries + "/" + entryId), strangerToken, null, 403);
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
