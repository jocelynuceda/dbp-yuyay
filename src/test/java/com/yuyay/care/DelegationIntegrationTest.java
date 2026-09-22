package com.yuyay.care;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DelegationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String ownerToken;
    private long subjectId;
    private long medicationId;
    private long allergyId;

    @BeforeEach
    void setUp() throws Exception {
        ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app");
        subjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        medicationId = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                "{\"categoryCode\":\"MEDICATION\",\"title\":\"Losartán\",\"dose\":\"50 mg\",\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();
        allergyId = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"UNCERTAIN\"}", 201).get("id").asLong();
    }

    @Test
    void principalCreatesDelegationAndTokenIsReturnedOnce() throws Exception {
        JsonNode created = createDelegation("MEDICATION");

        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/delegations").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].granteeName").value("Enfermera Rosa"))
                .andExpect(jsonPath("$[0].categoryCodes[0]").value("MEDICATION"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].token").doesNotExist());

        mvc.perform(post("/api/v1/public/delegations/" + created.get("token").asText() + "/exchange"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.careSubjectName").value("Pedro"));
    }

    @Test
    void delegateSeesOnlyAllowedCategories() throws Exception {
        String delegateToken = exchange(createDelegation("MEDICATION"));

        mvc.perform(get("/api/v1/delegate/me").header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careSubjectId").value(subjectId));

        mvc.perform(get("/api/v1/delegate/health-entries").header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].categoryCode").value("MEDICATION"));

        mvc.perform(get("/api/v1/delegate/health-entries/" + medicationId).header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/delegate/health-entries/" + allergyId).header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/delegate/health-entries").param("category", "ALLERGY").header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void delegateTokenCannotUseCaregiverEndpoints() throws Exception {
        String delegateToken = exchange(createDelegation("MEDICATION"));

        mvc.perform(get("/api/v1/care-subjects/" + subjectId).header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void linkCanBeExchangedOnlyOnce() throws Exception {
        JsonNode created = createDelegation("MEDICATION");
        String token = created.get("token").asText();

        mvc.perform(post("/api/v1/public/delegations/" + token + "/exchange")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/delegations/" + token + "/exchange"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Este enlace ya fue utilizado"));
    }

    @Test
    void revokedDelegationStopsWorking() throws Exception {
        JsonNode created = createDelegation("MEDICATION");
        String delegateToken = exchange(created);
        long delegationId = created.get("delegation").get("id").asLong();

        mvc.perform(delete("/api/v1/delegations/" + delegationId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/delegate/health-entries").header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTokenReturns404AndCaregiverCannotDelegate() throws Exception {
        mvc.perform(post("/api/v1/public/delegations/no-existe/exchange")).andExpect(status().isNotFound());

        String caregiverToken = register("cg." + UUID.randomUUID() + "@yuyay.app");
        mvc.perform(post("/api/v1/care-subjects/" + subjectId + "/delegations")
                        .header("Authorization", "Bearer " + caregiverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(delegationBody("MEDICATION")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        mvc.perform(post("/api/v1/care-subjects/" + subjectId + "/delegations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granteeName\":\"X\",\"granteeEmail\":\"x@y.z\",\"categoryCodes\":[],\"validUntil\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/care-subjects/" + subjectId + "/delegations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(delegationBody("SURGERY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Categorías desconocidas: [SURGERY]"));
    }

    private JsonNode createDelegation(String category) throws Exception {
        return postJson("/api/v1/care-subjects/" + subjectId + "/delegations", ownerToken, delegationBody(category), 201);
    }

    private String exchange(JsonNode created) throws Exception {
        String body = mvc.perform(post("/api/v1/public/delegations/" + created.get("token").asText() + "/exchange"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private String delegationBody(String category) {
        return "{\"granteeName\":\"Enfermera Rosa\",\"granteeEmail\":\"rosa@clinica.pe\",\"categoryCodes\":[\"" + category + "\"],\"validUntil\":\""
                + Instant.now().plusSeconds(7 * 24 * 3600) + "\"}";
    }

    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}", 201).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String token, String body, int expected) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
