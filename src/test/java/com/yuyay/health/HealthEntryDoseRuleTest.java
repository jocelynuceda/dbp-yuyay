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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dosis y frecuencia solo tienen sentido en MEDICATION. En cualquier otra categoría
 * se rechazan con 400 al crear y al editar, para que no quede una "alergia de 500 mg".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEntryDoseRuleTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String token;
    private String entriesPath;

    @BeforeEach
    void setUp() throws Exception {
        token = register("dose." + UUID.randomUUID() + "@yuyay.app");
        long subjectId = send(post("/api/v1/care-subjects"),
                "{\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        entriesPath = "/api/v1/care-subjects/" + subjectId + "/health-entries";
    }

    @Test
    void medicationAcceptsDoseAndFrequency() throws Exception {
        send(post(entriesPath),
                "{\"categoryCode\":\"MEDICATION\",\"title\":\"Losartán\",\"dose\":\"50 mg\",\"frequency\":\"1 vez al día\",\"confidenceLevel\":\"CONFIRMED\"}",
                201);
    }

    @Test
    void nonMedicationWithDoseOrFrequencyReturns400() throws Exception {
        send(post(entriesPath),
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"dose\":\"500 mg\",\"confidenceLevel\":\"CONFIRMED\"}",
                400);
        send(post(entriesPath),
                "{\"categoryCode\":\"EPISODE\",\"title\":\"Mareo\",\"frequency\":\"diario\",\"confidenceLevel\":\"CONFIRMED\"}",
                400);

        mvc.perform(get(entriesPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void updatingNonMedicationWithDoseReturns400AndKeepsVersion() throws Exception {
        long entryId = send(post(entriesPath),
                "{\"categoryCode\":\"ALLERGY\",\"title\":\"Penicilina\",\"confidenceLevel\":\"CONFIRMED\"}",
                201).get("id").asLong();

        send(patch(entriesPath + "/" + entryId),
                "{\"title\":\"Penicilina\",\"dose\":\"500 mg\",\"confidenceLevel\":\"CONFIRMED\"}",
                400);

        mvc.perform(get(entriesPath + "/" + entryId + "/versions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    private String register(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}";
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asText();
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String body, int expected) throws Exception {
        String response = mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
