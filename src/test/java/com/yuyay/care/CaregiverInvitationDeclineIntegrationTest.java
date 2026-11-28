package com.yuyay.care;

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
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rechazo de una invitación de cuidador por parte del invitado: la relación pendiente se borra,
 * el aviso INVITATION queda leído y el principal puede volver a invitar a la misma persona.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaregiverInvitationDeclineIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void decliningRemovesTheInvitationFromThePendingList() throws Exception {
        String ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app", "Ana Torres");
        long subjectId = createSubject(ownerToken, "Lucía");
        String inviteeEmail = "invitee." + UUID.randomUUID() + "@yuyay.app";
        String inviteeToken = register(inviteeEmail, "Bruno Torres");
        long relationshipId = invite(ownerToken, subjectId, inviteeEmail, "CAREGIVER");
        assertTrue(isPending(inviteeToken, relationshipId));

        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/decline")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isNoContent());

        assertFalse(isPending(inviteeToken, relationshipId));
    }

    @Test
    void decliningMarksTheInvitationNoticeRead() throws Exception {
        String ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app", "Ana Torres");
        long subjectId = createSubject(ownerToken, "Rosa");
        String inviteeEmail = "invitee." + UUID.randomUUID() + "@yuyay.app";
        String inviteeToken = register(inviteeEmail, "Bruno Torres");
        long relationshipId = invite(ownerToken, subjectId, inviteeEmail, "CAREGIVER");
        assertEquals(1, unreadCount(inviteeToken));

        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/decline")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isNoContent());

        assertEquals(0, unreadCount(inviteeToken));
    }

    @Test
    void principalCanInviteTheSameEmailAgainAfterDecline() throws Exception {
        String ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app", "Ana Torres");
        long subjectId = createSubject(ownerToken, "Pedro");
        String inviteeEmail = "invitee." + UUID.randomUUID() + "@yuyay.app";
        String inviteeToken = register(inviteeEmail, "Bruno Torres");
        long firstRelationship = invite(ownerToken, subjectId, inviteeEmail, "CAREGIVER");

        mvc.perform(post("/api/v1/me/relationships/" + firstRelationship + "/decline")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isNoContent());

        long secondRelationship = invite(ownerToken, subjectId, inviteeEmail, "CAREGIVER");
        assertTrue(secondRelationship != firstRelationship);
        assertTrue(isPending(inviteeToken, secondRelationship));
    }

    @Test
    void anotherUserCannotDeclineSomeoneElsesInvitation() throws Exception {
        String ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app", "Ana Torres");
        long subjectId = createSubject(ownerToken, "Lucía");
        String inviteeEmail = "invitee." + UUID.randomUUID() + "@yuyay.app";
        String inviteeToken = register(inviteeEmail, "Bruno Torres");
        String strangerToken = register("stranger." + UUID.randomUUID() + "@yuyay.app", "Carla Ruiz");
        long relationshipId = invite(ownerToken, subjectId, inviteeEmail, "CAREGIVER");

        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/decline")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());

        assertTrue(isPending(inviteeToken, relationshipId));
    }

    @Test
    void decliningAnAlreadyAcceptedInvitationFailsAndKeepsItActive() throws Exception {
        String ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app", "Ana Torres");
        long subjectId = createSubject(ownerToken, "Rosa");
        String inviteeEmail = "invitee." + UUID.randomUUID() + "@yuyay.app";
        String inviteeToken = register(inviteeEmail, "Bruno Torres");
        long relationshipId = invite(ownerToken, subjectId, inviteeEmail, "CAREGIVER");
        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/accept")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/me/relationships/" + relationshipId + "/decline")
                        .header("Authorization", "Bearer " + inviteeToken))
                .andExpect(status().isBadRequest());

        JsonNode relationships = getJson("/api/v1/care-subjects/" + subjectId + "/relationships", ownerToken);
        JsonNode accepted = StreamSupport.stream(relationships.spliterator(), false)
                .filter(r -> r.get("id").asLong() == relationshipId)
                .findFirst().orElseThrow();
        assertEquals("ACTIVE", accepted.get("status").asText());
    }

    private boolean isPending(String token, long relationshipId) throws Exception {
        JsonNode pending = getJson("/api/v1/me/relationships/pending", token);
        return StreamSupport.stream(pending.spliterator(), false)
                .anyMatch(r -> r.get("id").asLong() == relationshipId);
    }

    private long createSubject(String token, String name) throws Exception {
        return postJson("/api/v1/care-subjects", token, """
                {"requestId":"%s","declaration":{"type":"FICTITIOUS","textVersion":"1","confirmed":true},"name":"%s"}
                """.formatted(UUID.randomUUID(), name), 201).get("id").asLong();
    }

    private long invite(String token, long subjectId, String email, String role) throws Exception {
        return postJson("/api/v1/care-subjects/" + subjectId + "/relationships", token,
                "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}", 201).get("id").asLong();
    }

    private long unreadCount(String token) throws Exception {
        return getJson("/api/v1/notifications/unread-count", token).get("unreadCount").asLong();
    }

    private String register(String email, String name) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.3\",\"fullName\":\"Persona Sintetica\",\"dni\":\"12345678\",\"password\":\"Secreta123\",\"name\":\"" + name + "\"}", 201)
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
