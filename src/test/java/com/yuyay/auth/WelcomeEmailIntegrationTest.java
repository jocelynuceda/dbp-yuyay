package com.yuyay.auth;

import com.yuyay.event.UserRegisteredEvent;
import com.yuyay.notification.email.EmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Al registrarse se publica UserRegisteredEvent y EmailListener envía la bienvenida
 * renderizada con la plantilla welcome.html. Un registro rechazado no envía nada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class WelcomeEmailIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEvents events;

    @MockitoBean EmailSender emailSender;

    @Test
    void registerPublishesEventAndSendsWelcomeEmail() throws Exception {
        String email = "welcome." + UUID.randomUUID() + "@yuyay.app";

        String response = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "Lucía")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long userId = json.readTree(response).get("user").get("id").asLong();

        List<UserRegisteredEvent> published = events.stream(UserRegisteredEvent.class).toList();
        assertEquals(1, published.size());
        assertEquals(userId, published.get(0).userId());

        verify(emailSender, timeout(5000))
                .send(eq(email), eq("Te damos la bienvenida a Yuyay"), contains("Lucía"));
    }

    @Test
    void duplicateRegisterDoesNotSendWelcomeEmail() throws Exception {
        String email = "dup." + UUID.randomUUID() + "@yuyay.app";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(email, "Ana")))
                .andExpect(status().isCreated());
        verify(emailSender, timeout(5000)).send(eq(email), anyString(), anyString());
        events.clear();

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(email, "Ana")))
                .andExpect(status().isConflict());

        assertTrue(events.stream(UserRegisteredEvent.class).findAny().isEmpty());
        verify(emailSender, after(500).times(1)).send(eq(email), anyString(), anyString());
    }

    private String body(String email, String name) {
        return "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}";
    }
}
