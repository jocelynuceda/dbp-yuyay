package com.yuyay.care;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.frontend-url=https://web.yuyay.example/")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DelegationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;

    private String ownerToken;
    private long subjectId;
    private long medicationId;
    private long allergyId;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app");
        subjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        medicationId = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                "{\"categoryCode\":\"MEDICATION\",\"title\":\"Losartán\",\"dose\":\"50 mg\",\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();
        allergyId = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"UNCERTAIN\"}", 201).get("id").asLong();
    }

    @Test
    void principalCreatesDelegationAndTokenIsReturnedOnce() throws Exception {
        JsonNode created = createDelegation("MEDICATION");
        assertEquals("https://web.yuyay.example/acceso/" + created.get("token").asText(),
                created.get("shareLink").asText());

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
    void secondaryCaregiverCanSeeButCannotRevokeAnActiveDelegation() throws Exception {
        String email = "secondary." + UUID.randomUUID() + "@yuyay.app";
        String secondary = register(email);
        long invitation = postJson("/api/v1/care-subjects/" + subjectId + "/relationships", ownerToken,
                json.writeValueAsString(Map.of("email", email, "role", "CAREGIVER")), 201).get("id").asLong();
        mvc.perform(post("/api/v1/me/relationships/" + invitation + "/accept")
                .header("Authorization", "Bearer " + secondary)).andExpect(status().isOk());
        JsonNode link = createDelegation("MEDICATION");
        String delegate = exchange(link);
        long id = link.get("delegation").get("id").asLong();
        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/delegations")
                .header("Authorization", "Bearer " + secondary)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(delete("/api/v1/delegations/" + id).header("Authorization", "Bearer " + secondary))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/delegate/me").header("Authorization", "Bearer " + delegate))
                .andExpect(status().isOk());
    }

    @Test
    void exchangeWaitsForPendingRevocation() throws Exception {
        JsonNode link = createDelegation("MEDICATION");
        overlap(revokeRequest(link), 204, exchangeRequest(link), 403);
    }

    @Test
    void revocationWaitsForPendingExchangeAndEndsIssuedSession() throws Exception {
        JsonNode link = createDelegation("MEDICATION");
        String body = overlap(exchangeRequest(link), 200, revokeRequest(link), 204);
        String delegateToken = json.readTree(body).get("accessToken").asText();
        mvc.perform(get("/api/v1/delegate/me").header("Authorization", "Bearer " + delegateToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentExchangesIssueOnlyOneSession() throws Exception {
        JsonNode link = createDelegation("MEDICATION");
        overlap(exchangeRequest(link), 200, exchangeRequest(link), 400);
    }

    private RequestBuilder revokeRequest(JsonNode link) {
        return delete("/api/v1/delegations/" + link.get("delegation").get("id").asLong())
                .header("Authorization", "Bearer " + ownerToken);
    }

    private RequestBuilder exchangeRequest(JsonNode link) {
        return post("/api/v1/public/delegations/" + link.get("token").asText() + "/exchange");
    }

    private String overlap(RequestBuilder first, int firstStatus, RequestBuilder second, int secondStatus) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var started = new CountDownLatch(1);
            record Pending(String body, Future<Integer> result) {}
            Pending pending = new TransactionTemplate(transactions).execute(tx -> {
                try {
                    var response = mvc.perform(first).andExpect(status().is(firstStatus)).andReturn().getResponse();
                    Future<Integer> result = worker.submit(() -> {
                        started.countDown();
                        return mvc.perform(second).andReturn().getResponse().getStatus();
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS));
                    return new Pending(response.getContentAsString(), result);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals(secondStatus, pending.result().get(10, TimeUnit.SECONDS));
            return pending.body();
        }
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

    @Test
    void validatesExactExpirationAt365DaysEvenAcrossLeapDay() throws Exception {
        Instant now = Instant.parse("2028-03-01T04:30:00Z");
        when(clock.instant()).thenReturn(now);
        String path = "/api/v1/care-subjects/" + subjectId + "/delegations";

        Instant limit = now.plusSeconds(365L * 24 * 60 * 60);
        for (Instant validUntil : List.of(now.plusSeconds(60), limit)) {
            JsonNode created = postJson(path, ownerToken, delegationBody("MEDICATION", validUntil), 201);
            assertEquals(validUntil, Instant.parse(created.get("delegation").get("validUntil").asText()));
        }

        for (Instant requested : List.of(limit.plusSeconds(1), limit.plusSeconds(3600))) {
            JsonNode created = postJson(path, ownerToken, delegationBody("MEDICATION", requested), 201);
            assertEquals(limit, Instant.parse(created.get("delegation").get("validUntil").asText()));
            mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == " + created.get("delegation").get("id").asLong()
                            + ")].validUntil", hasItem(limit.toString())));
        }

        for (Instant invalidUntil : List.of(now, limit.plusSeconds(3600).plusMillis(1))) {
            mvc.perform(post(path).header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(delegationBody("MEDICATION", invalidUntil)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("validUntil")));
        }
    }

    @Test
    void consultationsAreVisibleOnlyWhenIncludedAndWithoutNotes() throws Exception {
        postJson("/api/v1/care-subjects/" + subjectId + "/consultations", ownerToken,
                "{\"date\":\"2099-05-10\",\"reason\":\"Control de presión\",\"notes\":\"Nota privada\"}", 201);

        JsonNode withConsultations = postJson("/api/v1/care-subjects/" + subjectId + "/delegations", ownerToken,
                "{\"granteeName\":\"Rosa\",\"granteeEmail\":\"rosa@clinica.pe\",\"categoryCodes\":[],"
                        + "\"includeConsultations\":true,\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}", 201);
        String allowed = exchange(withConsultations);

        mvc.perform(get("/api/v1/delegate/consultations").header("Authorization", "Bearer " + allowed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("Control de presión"))
                .andExpect(jsonPath("$[0].notes").doesNotExist());
        mvc.perform(get("/api/v1/delegate/health-entries").header("Authorization", "Bearer " + allowed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String denied = exchange(createDelegation("MEDICATION"));
        mvc.perform(get("/api/v1/delegate/consultations").header("Authorization", "Bearer " + denied))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/consultations").header("Authorization", "Bearer " + allowed))
                .andExpect(status().isForbidden());
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
        return delegationBody(category, Instant.now().plusSeconds(7 * 24 * 3600));
    }

    private String delegationBody(String category, Instant validUntil) {
        return "{\"granteeName\":\"Enfermera Rosa\",\"granteeEmail\":\"rosa@clinica.pe\",\"categoryCodes\":[\"" + category + "\"],\"validUntil\":\""
                + validUntil + "\"}";
    }

    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.2\",\"fullName\":\"Persona Sintetica\",\"dni\":\"12345678\",\"password\":\"Secreta123\",\"name\":\"Test\"}", 201).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String token, String body, int expected) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
