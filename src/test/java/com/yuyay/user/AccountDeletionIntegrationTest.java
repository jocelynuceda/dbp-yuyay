package com.yuyay.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Eliminar la cuenta")
class AccountDeletionIntegrationTest {

    private static final String SUBJECTS = "/api/v1/care-subjects";
    private static final String PREVIEW = "/api/v1/users/me/deletion-preview";
    private static final String DELETE_ME = "/api/v1/users/me";
    private static final String WORD = "ELIMINAR";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("sin sesión no se puede ver ni ejecutar el borrado")
    void withoutASessionNothingIsVisibleAndNothingIsDeleted() throws Exception {
        send(get(PREVIEW), null, null, 401);
        send(delete(DELETE_ME), null, Map.of("confirmation", WORD, "profileIds", List.of()), 401);
    }

    @Test
    @DisplayName("una cuenta sin perfiles no pierde nada y se puede eliminar")
    void anAccountWithoutProfilesLosesNothing() throws Exception {
        Session account = register();

        JsonNode preview = send(get(PREVIEW), account, null, 200);

        assertTrue(preview.get("profilesToDelete").isEmpty());
        assertTrue(preview.get("profilesKept").isEmpty());
        assertTrue(preview.get("blockers").isEmpty());
        assertEquals(WORD, preview.get("confirmationWord").asText());
    }

    @Test
    @DisplayName("la palabra de confirmación equivocada no borra nada")
    void theWrongConfirmationWordDeletesNothing() throws Exception {
        Session account = register();
        long id = createSubject(account, "Ana Quispe");

        send(delete(DELETE_ME), account, Map.of("confirmation", "eliminar", "profileIds", List.of(id)), 400);

        assertEquals(200, statusOf(get(SUBJECTS + "/" + id), account));
    }

    @Test
    @DisplayName("si la lista de perfiles no coincide con la vista previa, se rechaza")
    void aListThatDoesNotMatchThePreviewIsRejected() throws Exception {
        Session account = register();
        long id = createSubject(account, "Ana Quispe");

        // Confirma sin incluir el perfil que iba a perder.
        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of()), 409);

        assertEquals(200, statusOf(get(SUBJECTS + "/" + id), account));
    }

    @Test
    @DisplayName("un perfil con historial de salud bloquea el borrado y lo dice antes")
    void aProfileWithHealthHistoryBlocksTheDeletionAndSaysSoFirst() throws Exception {
        Session account = register();
        long id = createSubject(account, "Ana Quispe");
        send(post(SUBJECTS + "/" + id + "/health-entries"), account, Map.of(
                "categoryCode", "MEDICATION", "title", "Losartán",
                "dose", "50 mg", "confidenceLevel", "CONFIRMED"), 201);

        JsonNode preview = send(get(PREVIEW), account, null, 200);
        JsonNode profile = preview.get("profilesToDelete").get(0);
        assertTrue(profile.get("hasHistory").asBoolean());
        assertEquals(1, profile.get("entryCount").asLong());
        assertFalse(preview.get("blockers").isEmpty());

        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of(id)), 409);

        assertEquals(200, statusOf(get(SUBJECTS + "/" + id), account));
    }

    @Test
    @DisplayName("el borrado se lleva los perfiles confirmados, cierra el acceso y libera el correo")
    void theDeletionTakesTheConfirmedProfilesAndClosesTheAccess() throws Exception {
        String email = "borrado." + UUID.randomUUID() + "@yuyay.app";
        Session account = register(email);
        long id = createSubject(account, "Ana Quispe");

        JsonNode preview = send(get(PREVIEW), account, null, 200);
        assertEquals(1, preview.get("profilesToDelete").size());

        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of(id)), 204);

        // El perfil ya no existe y el token de acceso que ya tenía deja de servir.
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from care_subjects where id = ?", Integer.class, id));
        send(get(PREVIEW), account, null, 401);
        send(post("/api/v1/auth/login"), null, Map.of("email", email, "password", "Secreta123"), 401);

        // El correo queda libre: la misma persona puede volver a registrarse.
        send(post("/api/v1/auth/register"), null, registration(email), 201);
    }

    @Test
    @DisplayName("la lápida no conserva el dato identificante")
    void theTombstoneKeepsNoPersonalData() throws Exception {
        String email = "lapida." + UUID.randomUUID() + "@yuyay.app";
        Session account = register(email);

        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of()), 204);

        Map<String, Object> row = jdbc.queryForMap(
                "select email, name, full_name, password_hash, google_subject, deleted_at from users where id = ?",
                account.id());
        assertEquals("eliminado+" + account.id() + "@yuyay.invalid", row.get("email"));
        assertEquals("Cuenta eliminada", row.get("name"));
        assertNull(row.get("full_name"));
        assertNull(row.get("password_hash"));
        assertNull(row.get("google_subject"));
        assertFalse(row.get("deleted_at") == null);
    }

    @Test
    @DisplayName("un perfil con otro responsable sobrevive y el otro sigue viéndolo")
    void aProfileWithAnotherResponsibleSurvives() throws Exception {
        Session owner = register();
        Session other = register();
        long id = createSubject(owner, "Ana Quispe");
        invitePrincipal(owner, other, id);

        JsonNode preview = send(get(PREVIEW), owner, null, 200);
        assertTrue(preview.get("profilesToDelete").isEmpty());
        assertEquals(1, preview.get("profilesKept").size());
        assertEquals(id, preview.get("profilesKept").get(0).get("id").asLong());

        send(delete(DELETE_ME), owner, Map.of("confirmation", WORD, "profileIds", List.of()), 204);

        assertEquals(200, statusOf(get(SUBJECTS + "/" + id), other));
    }

    @Test
    @DisplayName("las declaraciones que hizo sobre un perfil que sobrevive no se borran")
    void theDeclarationsAboutASurvivingProfileAreKept() throws Exception {
        Session owner = register();
        Session other = register();
        long id = createSubject(owner, "Ana Quispe");
        invitePrincipal(owner, other, id);

        JsonNode preview = send(get(PREVIEW), owner, null, 200);
        assertTrue(preview.get("declarationsKept").asLong() >= 1);

        send(delete(DELETE_ME), owner, Map.of("confirmation", WORD, "profileIds", List.of()), 204);

        assertTrue(jdbc.queryForObject(
                "select count(*) from care_authorization_declarations where declared_by_user_id = ?",
                Integer.class, owner.id()) >= 1);
    }

    // --- apoyo ---

    private record Session(String token, Long id, String email) {}

    private Session register() throws Exception {
        return register("borrado." + UUID.randomUUID() + "@yuyay.app");
    }

    private Session register(String email) throws Exception {
        JsonNode auth = send(post("/api/v1/auth/register"), null, registration(email), 201);
        return new Session(auth.get("accessToken").asText(), auth.get("user").get("id").asLong(), email);
    }

    private static Map<String, Object> registration(String email) {
        return Map.of(
                "email", email, "password", "Secreta123", "name", "Prueba",
                "termsAccepted", true, "termsVersion", "1.2",
                "fullName", "Persona Sintetica", "dni", "12345678");
    }

    private long createSubject(Session actor, String name) throws Exception {
        return send(post(SUBJECTS), actor, Map.of(
                "requestId", UUID.randomUUID().toString(),
                "name", name,
                "declaration", Map.of("type", "HOLDER_PERMISSION", "textVersion", "1", "confirmed", true)),
                201).get("id").asLong();
    }

    /** Deja a {@code invited} como PRINCIPAL activo del perfil, igual que el otro responsable. */
    private void invitePrincipal(Session inviter, Session invited, long subjectId) throws Exception {
        long relationshipId = send(post(SUBJECTS + "/" + subjectId + "/relationships"), inviter, Map.of(
                "email", invited.email(), "role", "PRINCIPAL"), 201).get("id").asLong();
        send(post("/api/v1/me/relationships/" + relationshipId + "/accept"), invited, null, 200);
    }

    private int statusOf(MockHttpServletRequestBuilder req, Session actor) throws Exception {
        req.header("Authorization", "Bearer " + actor.token());
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private JsonNode send(MockHttpServletRequestBuilder req, Session actor, Object body, int expected)
            throws Exception {
        if (actor != null) req.header("Authorization", "Bearer " + actor.token());
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String result = mvc.perform(req).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return result.isBlank() ? null : json.readTree(result);
    }
}
