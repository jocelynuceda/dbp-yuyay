package com.yuyay.user;

import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Fecha de nacimiento de la cuenta (para la edad y el avatar automático)")
class BirthDateIntegrationTest {

    /** La que devuelve el directorio sintético al registrarse. */
    private static final String DNI_BIRTH_DATE = "1990-01-01";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("guarda la fecha, la conserva si se omite y la borra con null explícito")
    void savedFromTheProfileAndKept() throws Exception {
        String token = register();
        assertEquals(DNI_BIRTH_DATE, send(get("/api/v1/users/me"), token, null, 200).get("birthDate").asText());

        JsonNode updated = send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana\",\"birthDate\":\"1981-05-10\"}", 200);
        assertEquals("1981-05-10", updated.get("birthDate").asText());

        send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana Torres\"}", 200);
        assertEquals("1981-05-10", send(get("/api/v1/users/me"), token, null, 200).get("birthDate").asText());

        JsonNode cleared = send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana Torres\",\"birthDate\":null}", 200);
        assertTrue(cleared.get("birthDate").isNull());
        assertTrue(send(get("/api/v1/users/me"), token, null, 200).get("birthDate").isNull());
    }

    @Test
    @DisplayName("rechaza una fecha futura o anterior a 1900")
    void rejectsImpossibleDates() throws Exception {
        String token = register();
        send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana\",\"birthDate\":\"" + LocalDate.now().plusDays(1) + "\"}", 400);
        send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana\",\"birthDate\":\"1899-12-31\"}", 409);
        assertEquals(DNI_BIRTH_DATE, send(get("/api/v1/users/me"), token, null, 200).get("birthDate").asText());
    }

    @Test
    @DisplayName("ignora la bandera interna y toma en cuenta solo la presencia de birthDate")
    void ignoresClientSuppliedBirthDateFlag() throws Exception {
        String token = register();
        send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana\",\"birthDate\":\"1981-05-10\"}", 200);

        send(patch("/api/v1/users/me"), token, "{\"name\":\"Ana Torres\",\"birthDateProvided\":true}", 200);
        assertEquals("1981-05-10", send(get("/api/v1/users/me"), token, null, 200).get("birthDate").asText());

        send(patch("/api/v1/users/me"), token,
                "{\"name\":\"Ana Torres\",\"birthDate\":null,\"birthDateProvided\":false}", 200);
        assertTrue(send(get("/api/v1/users/me"), token, null, 200).get("birthDate").isNull());
    }

    private String register() throws Exception {
        return send(post("/api/v1/auth/register"), null, "{\"email\":\"edad." + UUID.randomUUID() + "@yuyay.app\","
                + "\"password\":\"Secreta123\",\"name\":\"Ana\",\"termsAccepted\":true,\"termsVersion\":\"1.3\","
                // El registro contrasta el DNI; en pruebas responde el directorio sintético
                + "\"dni\":\"12345678\",\"fullName\":\"Persona Sintetica\"}", 201)
                .get("accessToken").asText();
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String token, String body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? null : json.readTree(response);
    }
}
