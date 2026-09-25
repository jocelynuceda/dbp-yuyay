package com.yuyay.audit;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La bitácora registra lo que hacen los cuidadores sobre los registros de salud:
 * quién creó, vio, editó o borró cada entrada. Listar no deja rastro.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessLogIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String anaToken;
    private long anaId;
    private String brunoToken;
    private long brunoId;
    private long subjectId;

    @BeforeEach
    void setUp() throws Exception {
        String anaEmail = "ana." + UUID.randomUUID() + "@yuyay.app";
        String brunoEmail = "bruno." + UUID.randomUUID() + "@yuyay.app";
        JsonNode ana = register(anaEmail, "Ana");
        JsonNode bruno = register(brunoEmail, "Bruno");
        anaToken = ana.get("accessToken").asText();
        anaId = ana.get("user").get("id").asLong();
        brunoToken = bruno.get("accessToken").asText();
        brunoId = bruno.get("user").get("id").asLong();

        subjectId = send(post("/api/v1/care-subjects"), anaToken,
                "{\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        long relationshipId = send(post("/api/v1/care-subjects/" + subjectId + "/relationships"), anaToken,
                "{\"email\":\"" + brunoEmail + "\",\"role\":\"CAREGIVER\"}", 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + relationshipId + "/accept"), brunoToken, "{}", 200);
    }

    @Test
    void caregiverActionsOnHealthEntriesAreLogged() throws Exception {
        String entries = "/api/v1/care-subjects/" + subjectId + "/health-entries";
        long entryId = send(post(entries), brunoToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();
        send(get(entries), brunoToken, null, 200);
        send(get(entries + "/" + entryId), brunoToken, null, 200);
        send(patch(entries + "/" + entryId), brunoToken,
                "{\"title\":\"Penicilina\",\"details\":\"Ronchas\",\"confidenceLevel\":\"CONFIRMED\"}", 200);
        send(delete(entries + "/" + entryId), anaToken, null, 204);

        List<JsonNode> logs = waitForHealthEntryLogs(4);

        // Los listeners corren en paralelo, así que se compara por acción y no por orden.
        Map<String, Long> userByAction = logs.stream()
                .collect(Collectors.toMap(l -> l.get("action").asText(), l -> l.get("userId").asLong()));
        assertEquals(Map.of("CREATE", brunoId, "VIEW", brunoId, "UPDATE", brunoId, "DELETE", anaId), userByAction);
        logs.forEach(l -> assertEquals(entryId, l.get("targetId").asLong()));
    }

    @Test
    void caregiverCannotReadAccessLogs() throws Exception {
        send(get("/api/v1/care-subjects/" + subjectId + "/access-logs"), brunoToken, null, 403);
    }

    /** Los listeners son asíncronos (AFTER_COMMIT + @Async): se espera hasta que lleguen. */
    private List<JsonNode> waitForHealthEntryLogs(int expected) throws Exception {
        List<JsonNode> logs = List.of();
        for (int i = 0; i < 50 && logs.size() < expected; i++) {
            Thread.sleep(100);
            JsonNode all = send(get("/api/v1/care-subjects/" + subjectId + "/access-logs"), anaToken, null, 200);
            List<JsonNode> found = new ArrayList<>();
            all.forEach(l -> {
                if ("HEALTH_ENTRY".equals(l.get("targetType").asText())) found.add(l);
            });
            logs = found;
        }
        assertEquals(expected, logs.size(), "entradas de bitácora de HEALTH_ENTRY");
        return logs;
    }

    private JsonNode register(String email, String name) throws Exception {
        return send(post("/api/v1/auth/register"), null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201);
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
