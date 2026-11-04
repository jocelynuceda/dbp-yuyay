package com.yuyay.auth;

import com.yuyay.auth.service.AuthService;
import com.yuyay.exception.InvalidTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthRegressionTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;

    @Test
    void originalPasswordSurvivesRegisterLogoutAndLoginWithUnicodeAndSpaces() throws Exception {
        String email = uniqueEmail();
        String password = " Synthetic_123_ñ🩺 ";
        var registered = request("/register", Map.of("email", email, "password", password, "name", "Synthetic"), 201);
        assertFalse(registered.has("passwordHash"));
        var login = request("/login", Map.of("email", email.toUpperCase(), "password", password), 200);
        assertFalse(login.has("password"));
        assertFalse(login.has("passwordHash"));
        assertFalse(login.get("user").has("passwordHash"));
        request("/login", Map.of("email", email, "password", password.trim()), 401);
        request("/register", Map.of("email", email, "password", password, "name", "Duplicate"), 409);
        request("/logout", Map.of("refreshToken", registered.get("refreshToken").asText()), 204);
        request("/refresh", Map.of("refreshToken", registered.get("refreshToken").asText()), 401);
        request("/login", Map.of("email", email, "password", password), 200);
    }

    @Test
    void bcryptByteBoundaryReturnsFieldErrorWithoutTruncatingUnicode() throws Exception {
        String valid = "A1" + "ñ".repeat(35);
        String email = uniqueEmail();
        request("/register", Map.of("email", email, "password", valid, "name", "Boundary"), 201);
        request("/login", Map.of("email", email, "password", valid), 200);
        var invalid = request("/register", Map.of("email", uniqueEmail(), "password", valid + "ñ", "name", "Boundary"), 400);
        assertEquals("password", invalid.get("fieldErrors").get(0).get("field").asText());
        request("/login", Map.of("email", email, "password", valid + "ñ"), 401);
        String ascii = "A1" + "x".repeat(70);
        String asciiEmail = uniqueEmail();
        request("/register", Map.of("email", asciiEmail, "password", ascii, "name", "ASCII"), 201);
        request("/login", Map.of("email", asciiEmail, "password", ascii), 200);
    }

    @Test
    void simultaneousRefreshRequestsCanRotateOnlyOnce() throws Exception {
        var registered = request("/register", Map.of("email", uniqueEmail(), "password", "Synthetic_123", "name", "Concurrent"), 201);
        String token = registered.get("refreshToken").asText();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    auth.refresh(token);
                    return true;
                } catch (InvalidTokenException expected) {
                    return false;
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int success = 0;
            for (var job : jobs) if (job.get(10, TimeUnit.SECONDS)) success++;
            assertEquals(1, success, "A refresh token must be single use even across concurrent requests");
        }
    }

    private String uniqueEmail() {
        return "auth." + UUID.randomUUID() + "@example.invalid";
    }

    private tools.jackson.databind.JsonNode request(String path, Map<String, String> body, int expected) throws Exception {
        var response = mvc.perform(post("/api/v1/auth" + path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn().getResponse();
        // Compare statuses directly so a failed assertion does not dump passwords or tokens.
        assertEquals(expected, response.getStatus(), "Status at " + path);
        return response.getContentAsString().isEmpty() ? null : json.readTree(response.getContentAsString());
    }
}
