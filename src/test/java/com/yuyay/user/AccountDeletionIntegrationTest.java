package com.yuyay.user;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.storage.FileStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @Autowired UserRepository users;

    // Sin almacenamiento ni lectura de texto reales: solo importa qué se borra y cuándo
    @MockitoBean FileStorage fileStorage;
    @MockitoBean TextExtractor textExtractor;

    @Test
    @DisplayName("sin sesión no se puede ver ni ejecutar el borrado")
    void withoutASessionNothingIsVisibleAndNothingIsDeleted() throws Exception {
        send(get(PREVIEW), null, null, 401);
        send(delete(DELETE_ME), null, Map.of("confirmation", WORD, "profileIds", List.of()), 401);
    }

    @Test
    @DisplayName("una cuenta nueva solo tiene su perfil inicial vacío para confirmar el borrado")
    void aNewAccountListsItsEmptyOwnProfile() throws Exception {
        Session account = register();

        JsonNode preview = send(get(PREVIEW), account, null, 200);

        assertEquals(1, preview.get("profilesToDelete").size());
        assertEquals(ownId(account), preview.get("profilesToDelete").get(0).get("id").asLong());
        assertFalse(preview.get("profilesToDelete").get(0).get("hasHistory").asBoolean());
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
    @DisplayName("un identificador nulo se rechaza y deja la cuenta activa")
    void rejectsNullProfileIdsWithoutDeletingTheAccount() throws Exception {
        Session account = register();
        send(delete(DELETE_ME), account, Map.of(
                "confirmation", WORD, "profileIds", Collections.singletonList(null)), 400);

        assertEquals(account.id().longValue(), send(get(DELETE_ME), account, null, 200).get("id").asLong());
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
        JsonNode profile = profile(preview, id);
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
        assertEquals(2, preview.get("profilesToDelete").size());

        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of(ownId(account), id)), 204);

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
        JsonNode updated = send(patch(DELETE_ME), account, Map.of("name", "Prueba", "birthDate", "1981-05-10"), 200);
        assertEquals("1981-05-10", updated.get("birthDate").asText());

        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of(ownId(account))), 204);

        Map<String, Object> row = jdbc.queryForMap(
                "select email, name, full_name, password_hash, google_subject, birth_date, deleted_at from users where id = ?",
                account.id());
        assertEquals("eliminado+" + account.id() + "@yuyay.invalid", row.get("email"));
        assertEquals("Cuenta eliminada", row.get("name"));
        assertNull(row.get("full_name"));
        assertNull(row.get("password_hash"));
        assertNull(row.get("google_subject"));
        assertNull(row.get("birth_date"));
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
        assertEquals(1, preview.get("profilesToDelete").size());
        assertEquals(ownId(owner), preview.get("profilesToDelete").get(0).get("id").asLong());
        assertEquals(1, preview.get("profilesKept").size());
        assertEquals(id, preview.get("profilesKept").get(0).get("id").asLong());

        send(delete(DELETE_ME), owner, Map.of("confirmation", WORD, "profileIds", List.of(ownId(owner))), 204);

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

        send(delete(DELETE_ME), owner, Map.of("confirmation", WORD, "profileIds", List.of(ownId(owner))), 204);

        assertTrue(jdbc.queryForObject(
                "select count(*) from care_authorization_declarations where declared_by_user_id = ?",
                Integer.class, owner.id()) >= 1);
    }

    @Test
    @DisplayName("un perfil con documentos pero sin registros se borra con sus documentos")
    void aProfileWithDocumentsIsDeletedWithThem() throws Exception {
        Session account = register();
        long id = createSubject(account, "Ana Quispe");
        long documentId = uploadDocument(account, id);
        String storageKey = jdbc.queryForObject("select storage_key from attachments where id = ?", String.class, documentId);

        JsonNode preview = send(get(PREVIEW), account, null, 200);
        assertEquals(1, profile(preview, id).get("documentCount").asLong());
        assertTrue(preview.get("blockers").isEmpty());

        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of(ownId(account), id)), 204);

        assertEquals(0, jdbc.queryForObject("select count(*) from attachments where care_subject_id = ?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from care_subjects where id = ?", Integer.class, id));
        // El archivo se borra del almacenamiento después de confirmar, no antes
        verify(fileStorage).delete(storageKey);
    }

    @Test
    @DisplayName("un documento que se está leyendo bloquea el borrado y lo dice antes")
    void aDocumentBeingReadBlocksTheDeletion() throws Exception {
        Session account = register();
        long id = createSubject(account, "Ana Quispe");
        long documentId = uploadDocument(account, id);
        jdbc.update("update attachments set ocr_status = 'PENDING' where id = ?", documentId);

        JsonNode preview = send(get(PREVIEW), account, null, 200);
        assertTrue(preview.get("blockers").get(0).asText().contains("Se está leyendo un documento"));
        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of(id)), 409);

        // Nada cambió: la cuenta sigue entrando y el documento sigue ahí
        send(get(PREVIEW), account, null, 200);
        assertEquals(1, jdbc.queryForObject("select count(*) from attachments where id = ?", Integer.class, documentId));
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    @DisplayName("una cuenta anterior sin perfiles conserva su vista previa vacía y puede eliminarse")
    void aLegacyAccountWithoutProfilesCanStillBeDeleted() throws Exception {
        String email = "legacy-delete." + UUID.randomUUID() + "@example.invalid";
        var previous = users.save(User.builder().email(email)
                .name("Cuenta anterior ficticia")
                .passwordHash(new BCryptPasswordEncoder().encode("Secreta123"))
                .build());
        JsonNode auth = send(post("/api/v1/auth/login"), null,
                Map.of("email", email, "password", "Secreta123"), 200);
        Session account = new Session(auth.get("accessToken").asText(), previous.getId(), email);
        JsonNode preview = send(get(PREVIEW), account, null, 200);
        assertTrue(preview.get("profilesToDelete").isEmpty());
        assertTrue(preview.get("profilesKept").isEmpty());
        assertTrue(preview.get("blockers").isEmpty());
        send(delete(DELETE_ME), account, Map.of("confirmation", WORD, "profileIds", List.of()), 204);
    }

    private long ownId(Session account) {
        return jdbc.queryForObject("select id from care_subjects where account_user_id = ?", Long.class, account.id());
    }

    private JsonNode profile(JsonNode preview, long id) {
        for (JsonNode candidate : preview.get("profilesToDelete")) {
            if (candidate.get("id").asLong() == id) return candidate;
        }
        throw new AssertionError("El perfil esperado debe figurar en la vista previa");
    }

    // --- apoyo ---

    /** Sube una foto y espera a que termine su lectura de texto, como en la app. */
    private long uploadDocument(Session actor, long subjectId) throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("Receta de ejemplo");
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(30, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        MockMultipartFile file = new MockMultipartFile("file", "receta.png", "image/png", out.toByteArray());
        String body = mvc.perform(multipart(SUBJECTS + "/" + subjectId + "/attachments").file(file)
                        .param("source", "camera").header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long documentId = json.readTree(body).get("id").asLong();
        for (int i = 0; i < 100 && "PENDING".equals(
                jdbc.queryForObject("select ocr_status from attachments where id = ?", String.class, documentId)); i++) {
            Thread.sleep(20);
        }
        return documentId;
    }

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
                "termsAccepted", true, "termsVersion", "1.3",
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
