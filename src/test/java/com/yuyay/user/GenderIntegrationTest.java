package com.yuyay.user;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El género es opcional al registrarse y editable después, tanto en la cuenta
 * (User) como en la persona cuidada (CareSubject).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GenderIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void registerAcceptsGenderAndMeReturnsIt() throws Exception {
        JsonNode auth = send(post("/api/v1/auth/register"), null,
                body("ana", "Ana", "\"gender\":\"FEMALE\","), 201);
        String token = auth.get("accessToken").asText();

        assertEquals("FEMALE", auth.get("user").get("gender").asText());
        assertEquals("FEMALE", send(get("/api/v1/users/me"), token, null, 200).get("gender").asText());
    }

    @Test
    void registerWithoutGenderLeavesItNull() throws Exception {
        String token = send(post("/api/v1/auth/register"), null, body("bruno", "Bruno", ""), 201)
                .get("accessToken").asText();

        assertTrue(send(get("/api/v1/users/me"), token, null, 200).get("gender").isNull());
    }

    @Test
    void profileUpdateSetsGenderAndKeepsItWhenOmitted() throws Exception {
        String token = send(post("/api/v1/auth/register"), null, body("carla", "Carla", ""), 201)
                .get("accessToken").asText();

        JsonNode updated = send(patch("/api/v1/users/me"), token,
                "{\"name\":\"Carla Ruiz\",\"gender\":\"PREFER_NOT_TO_SAY\"}", 200);
        assertEquals("Carla Ruiz", updated.get("name").asText());
        assertEquals("PREFER_NOT_TO_SAY", updated.get("gender").asText());

        JsonNode withoutGender = send(patch("/api/v1/users/me"), token, "{\"name\":\"Carla R.\"}", 200);
        assertEquals("PREFER_NOT_TO_SAY", withoutGender.get("gender").asText());
    }

    @Test
    void unknownGenderValueReturns400() throws Exception {
        send(post("/api/v1/auth/register"), null, body("dana", "Dana", "\"gender\":\"UNICORNIO\","), 400);
    }

    @Test
    void careSubjectKeepsItsOwnGender() throws Exception {
        String token = send(post("/api/v1/auth/register"), null, body("elena", "Elena", "\"gender\":\"FEMALE\","), 201)
                .get("accessToken").asText();

        JsonNode subject = send(post("/api/v1/care-subjects"), token,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Pedro\",\"birthDate\":\"1948-03-12\",\"gender\":\"MALE\"}", 201);
        long subjectId = subject.get("id").asLong();
        assertEquals("MALE", subject.get("gender").asText());

        assertEquals("MALE", send(get("/api/v1/care-subjects/" + subjectId), token, null, 200)
                .get("gender").asText());

        JsonNode updated = send(patch("/api/v1/care-subjects/" + subjectId), token,
                "{\"name\":\"Pedro Torres\",\"gender\":\"OTHER\"}", 200);
        assertEquals("OTHER", updated.get("gender").asText());
    }

    @Test
    void careSubjectUpdateKeepsGenderWhenOmitted() throws Exception {
        String token = send(post("/api/v1/auth/register"), null, body("gina", "Gina", ""), 201)
                .get("accessToken").asText();

        long subjectId = send(post("/api/v1/care-subjects"), token,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Rosa\",\"birthDate\":\"1950-06-01\",\"gender\":\"FEMALE\"}", 201).get("id").asLong();

        JsonNode withoutGender = send(patch("/api/v1/care-subjects/" + subjectId), token,
                "{\"name\":\"Rosa Quispe\",\"birthDate\":\"1950-06-01\"}", 200);
        assertEquals("Rosa Quispe", withoutGender.get("name").asText());
        assertEquals("FEMALE", withoutGender.get("gender").asText());

        JsonNode withNull = send(patch("/api/v1/care-subjects/" + subjectId), token,
                "{\"name\":\"Rosa Quispe\",\"birthDate\":\"1950-06-01\",\"gender\":null}", 200);
        assertEquals("FEMALE", withNull.get("gender").asText());

        assertEquals("FEMALE", send(get("/api/v1/care-subjects/" + subjectId), token, null, 200)
                .get("gender").asText());
    }

    @Test
    void emptyGenderReturns400() throws Exception {
        send(post("/api/v1/auth/register"), null, body("hugo", "Hugo", "\"gender\":\"\","), 400);

        String token = send(post("/api/v1/auth/register"), null, body("ines", "Ines", ""), 201)
                .get("accessToken").asText();
        send(patch("/api/v1/users/me"), token, "{\"name\":\"Ines\",\"gender\":\"\"}", 400);
        send(post("/api/v1/care-subjects"), token, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Luz\",\"gender\":\"\"}", 400);

        long subjectId = send(post("/api/v1/care-subjects"), token,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Luz\",\"gender\":\"FEMALE\"}", 201).get("id").asLong();
        send(patch("/api/v1/care-subjects/" + subjectId), token, "{\"name\":\"Luz\",\"gender\":\"\"}", 400);
        // El 400 no cambia nada: la persona conserva su género
        assertEquals("FEMALE", send(get("/api/v1/care-subjects/" + subjectId), token, null, 200)
                .get("gender").asText());
    }

    @Test
    void careSubjectWithoutGenderLeavesItNull() throws Exception {
        String token = send(post("/api/v1/auth/register"), null, body("fabio", "Fabio", ""), 201)
                .get("accessToken").asText();

        JsonNode subject = send(post("/api/v1/care-subjects"), token, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Rosa\"}", 201);
        assertTrue(subject.get("gender").isNull());
    }

    private String body(String prefix, String name, String genderField) {
        return "{\"email\":\"" + prefix + "." + UUID.randomUUID() + "@yuyay.app\""
                + ",\"termsAccepted\":true,\"termsVersion\":\"1.0\",\"password\":\"Secreta123\"," + genderField + "\"name\":\"" + name + "\"}";
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String token, String body, int expected) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
