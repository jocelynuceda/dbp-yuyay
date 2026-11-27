package com.yuyay.auth;

import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;

    private static final String REGISTER = """
            {"email": "%s", "password": "Secreta123", "name": "Ana", "fullName": "Persona Sintetica", "termsAccepted": true, "termsVersion": "1.2", "dni": "12345678"}
            """;

    @Test
    void registerReturns201WithTokensAndNoPasswordHash() throws Exception {
        Instant before = Instant.now();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER.formatted("ana1@yuyay.app")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("ana1@yuyay.app"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        var user = users.findByEmailIgnoreCase("ana1@yuyay.app").orElseThrow();
        assertEquals("1.2", user.getTermsVersion());
        assertTrue(user.getTermsAcceptedAt().isAfter(before));
        assertTrue(user.getTermsAcceptedAt().isBefore(Instant.now()));
    }

    @Test
    void publishesTheCurrentTermsInFullWithoutAuthentication() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/terms"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(jsonPath("$.version").value("1.2"))
                .andReturn().getResponse();
        String published = json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("text").asString();
        String resource = new ClassPathResource("terms/yuyay-1.2.txt").getContentAsString(StandardCharsets.UTF_8);
        assertEquals(resource, published);
    }

    @Test
    void registrationRejectsAbsentFalseOrCoercedAcceptanceAndInvalidVersion() throws Exception {
        String email = "terms-required@yuyay.app";
        String prefix = """
                {"email": "%s", "password": "Secreta123", "name": "Ana", "fullName": "Persona Sintetica"
                """.formatted(email);
        assertRegistrationError(prefix + "}", "TERMS_ACCEPTANCE_REQUIRED");
        assertRegistrationError(prefix + """
                ,"termsAccepted":false,"termsVersion":"1.2", "dni": "12345678"}
                """, "TERMS_ACCEPTANCE_REQUIRED");
        assertRegistrationError(prefix + """
                ,"termsAccepted":"true","termsVersion":"1.2", "dni": "12345678"}
                """, "TERMS_ACCEPTANCE_REQUIRED");
        assertRegistrationError(prefix + """
                ,"termsAccepted":true}
                """, "TERMS_VERSION_OUTDATED");
        assertRegistrationError(prefix + """
                ,"termsAccepted":true,"termsVersion":1.1}
                """, "TERMS_VERSION_OUTDATED");
        assertRegistrationError(prefix + """
                ,"termsAccepted":true,"termsVersion":"0.9"}
                """, "TERMS_VERSION_OUTDATED");
        assertTrue(users.findByEmailIgnoreCase(email).isEmpty());
    }

    @Test
    void googleWithoutConfiguredAudienceReturnsControlledUnavailable() throws Exception {
        mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":"synthetic"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GOOGLE_AUTH_UNAVAILABLE"));
    }

    private void assertRegistrationError(String body, String code) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER.formatted("dup@yuyay.app"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER.formatted("dup@yuyay.app")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void weakPasswordReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "x@yuyay.app", "password": "corta", "name": "X"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER.formatted("login@yuyay.app"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "login@yuyay.app", "password": "Incorrecta1"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithoutTokenReturns401Json() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void meWorksWithAccessTokenAndRefreshRotates() throws Exception {
        MvcResult reg = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER.formatted("me@yuyay.app"))).andReturn();
        JsonNode body = json.readTree(reg.getResponse().getContentAsString());
        String access = body.get("accessToken").asText();
        String refresh = body.get("refreshToken").asText();

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@yuyay.app"));

        String refreshBody = "{\"refreshToken\": \"" + refresh + "\"}";
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }
}
