package com.yuyay.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo de invitación por la API: al invitar, el invitado recibe un aviso INVITATION; al aceptar,
 * ese aviso queda leído y el principal recibe CAREGIVER_JOINED.
 * Los avisos se crean en listeners asíncronos después del commit, por eso se espera con un plazo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaregiverInvitationNotificationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void inviteNotifiesInviteeAndAcceptNotifiesPrincipal() throws Exception {
        String ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app", "Ana Torres");
        long subjectId = postJson("/api/v1/care-subjects", ownerToken, "{\"name\":\"Lucía\"}", 201).get("id").asLong();

        String inviteeEmail = "invitee." + UUID.randomUUID() + "@yuyay.app";
        String inviteeToken = register(inviteeEmail, "Bruno Torres");
        long relationshipId = postJson("/api/v1/care-subjects/" + subjectId + "/relationships", ownerToken,
                "{\"email\":\"" + inviteeEmail + "\",\"role\":\"CAREGIVER\",\"relationshipLabel\":\"Tío\"}", 201)
                .get("id").asLong();

        JsonNode invitation = waitForNotification(inviteeToken, n -> n.get("type").asText().equals("INVITATION"));
        assertEquals("Te invitaron a cuidar a Lucía", invitation.get("title").asText());
        assertEquals("Ana Torres te invitó a cuidar a Lucía. Acepta la invitación para ver sus registros.",
                invitation.get("body").asText());
        assertEquals(subjectId, invitation.get("careSubjectId").asLong());
        assertFalse(invitation.get("read").asBoolean());
        assertEquals(1, unreadCount(inviteeToken));

        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());

        // Se marca en la misma transacción del accept: no hace falta esperar.
        assertEquals(0, unreadCount(inviteeToken));

        JsonNode joined = waitForNotification(ownerToken, n -> n.get("type").asText().equals("CAREGIVER_JOINED"));
        assertEquals("Nuevo cuidador para Lucía", joined.get("title").asText());
        assertEquals("Bruno Torres aceptó la invitación y ahora cuida a Lucía.", joined.get("body").asText());
        assertEquals(subjectId, joined.get("careSubjectId").asLong());

        // El que acepta no recibe CAREGIVER_JOINED sobre sí mismo.
        JsonNode inviteeList = getJson("/api/v1/notifications?size=50", inviteeToken).get("content");
        assertTrue(StreamSupport.stream(inviteeList.spliterator(), false)
                .noneMatch(n -> n.get("type").asText().equals("CAREGIVER_JOINED")));
    }

    private JsonNode waitForNotification(String token, Predicate<JsonNode> match) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode content = getJson("/api/v1/notifications?size=50", token).get("content");
            for (JsonNode n : content) {
                if (match.test(n)) return n;
            }
            Thread.sleep(50);
        }
        return fail("No llegó el aviso esperado en 5 s");
    }

    private long unreadCount(String token) throws Exception {
        return getJson("/api/v1/notifications/unread-count", token).get("unreadCount").asLong();
    }

    private String register(String email, String name) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201)
                .get("accessToken").asText();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        String response = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode postJson(String path, String token, String body, int expected) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        String response = mvc.perform(req).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
