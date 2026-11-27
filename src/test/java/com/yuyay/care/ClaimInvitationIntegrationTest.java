package com.yuyay.care;

import com.yuyay.notification.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Entrega del perfil a su titular")
class ClaimInvitationIntegrationTest {

    private static final String BASE = "/api/v1/care-subjects";
    private static final String EXCHANGE = "/api/v1/claim-invitations/exchange";
    private static final String CLAIM_SUBJECT = "Te entregan tu perfil en Yuyay";
    private static final Pattern TOKEN = Pattern.compile("/entrega/([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    /** El enlace ya no vuelve en la respuesta: la única copia sale por correo. */
    @MockitoBean EmailSender emailSender;

    private Session caregiver;
    private Session child;

    @BeforeEach
    void setUp() throws Exception {
        caregiver = register();
        child = register();
    }

    // --- emisión ---

    @Test
    @DisplayName("antes de la edad mínima no se puede emitir")
    void beforeTheMinimumAgeTheInvitationCannotBeEmitted() throws Exception {
        long id = createSubject("Menor de edad", LocalDate.now().minusYears(12), "LEGAL_REPRESENTATION");

        JsonNode error = emit(id, child.email(), 409);

        assertTrue(error.get("message").asText().contains("16"));
    }

    @Test
    @DisplayName("sin fecha de nacimiento tampoco")
    void withoutABirthDateTheInvitationCannotBeEmitted() throws Exception {
        long id = createSubject("Sin fecha", null, "LEGAL_REPRESENTATION");

        emit(id, child.email(), 400);
    }

    @Test
    @DisplayName("un perfil ficticio no se entrega a nadie")
    void aFictitiousProfileIsNotHandedOver() throws Exception {
        long id = createSubject("Persona inventada", LocalDate.now().minusYears(20), "FICTITIOUS");

        emit(id, child.email(), 409);
    }

    @Test
    @DisplayName("quien no es PRINCIPAL no puede entregar el perfil")
    void someoneWhoIsNotPrincipalCannotHandOverTheProfile() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");

        send(post(BASE + "/" + id + "/claim-invitations"), child,
                Map.of("invitedEmail", child.email()), 403);
    }

    @Test
    @DisplayName("reemitir invalida el enlace anterior")
    void reemittingInvalidatesThePreviousLink() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String first = emitAndReadToken(id, child.email());

        emit(id, child.email(), 201);

        send(post(EXCHANGE), child, Map.of("token", first), 409);
    }

    // --- canje ---

    @Test
    @DisplayName("al canjear, quien recibe queda titular y quien entregó queda cuidador activo")
    void theExchangeMakesTheRecipientTitularAndKeepsTheCaregiver() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String token = emitAndReadToken(id, child.email());

        JsonNode claimed = send(post(EXCHANGE), child, Map.of("token", token), 200);
        assertEquals(id, claimed.get("careSubjectId").asLong());
        assertEquals("PRINCIPAL", claimed.get("role").asText());
        assertNotNull(claimed.get("claimedAt"));

        // El titular ve su propio historial.
        assertEquals(1, send(get(BASE), child, null, 200).size());

        // Quien entregó conserva acceso, degradado a cuidador.
        JsonNode relationships = send(get(BASE + "/" + id + "/relationships"), child, null, 200);
        assertEquals("CAREGIVER", roleOf(relationships, caregiver.id()));
        assertEquals("ACTIVE", statusOf(relationships, caregiver.id()));
        assertEquals("PRINCIPAL", roleOf(relationships, child.id()));
        send(get(BASE + "/" + id), caregiver, null, 200);

        // Su propia declaración queda registrada, y el historial anterior no se reescribe.
        JsonNode history = send(get(BASE + "/" + id + "/authorization-declarations"), child, null, 200);
        assertEquals(2, history.size());
        assertEquals("SELF", history.get(0).get("type").asText());
        assertEquals(child.id(), history.get(0).get("declaredByUserId").asLong());
        assertEquals("LEGAL_REPRESENTATION", history.get(1).get("type").asText());
        assertEquals(caregiver.id(), history.get(1).get("declaredByUserId").asLong());

        // Se avisa a los cuidadores activos.
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'PROFILE_CLAIMED' AND user_id = ?",
                Integer.class, caregiver.id()));
    }

    @Test
    @DisplayName("el enlace no sirve dos veces")
    void theLinkDoesNotWorkTwice() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String token = emitAndReadToken(id, child.email());

        send(post(EXCHANGE), child, Map.of("token", token), 200);
        send(post(EXCHANGE), child, Map.of("token", token), 409);
    }

    @Test
    @DisplayName("un enlace emitido para otra cuenta no se canjea")
    void aLinkEmittedForAnotherAccountIsRefused() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String token = emitAndReadToken(id, child.email());
        Session stranger = register();

        send(post(EXCHANGE), stranger, Map.of("token", token), 403);
    }

    @Test
    @DisplayName("un token inventado no existe")
    void anInventedTokenDoesNotExist() throws Exception {
        send(post(EXCHANGE), child, Map.of("token", "no-existe"), 404);
    }

    @Test
    @DisplayName("un perfil ya entregado no se vuelve a entregar")
    void aProfileAlreadyHandedOverCannotBeHandedAgain() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        send(post(EXCHANGE), child, Map.of("token", emitAndReadToken(id, child.email())), 200);

        send(post(BASE + "/" + id + "/claim-invitations"), child,
                Map.of("invitedEmail", child.email()), 409);
    }

    // --- la autonomía que gana el titular ---

    @Test
    @DisplayName("nadie puede quitarle el acceso al titular")
    void nobodyCanTakeTheTitularAccessAway() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        send(post(EXCHANGE), child, Map.of("token", emitAndReadToken(id, child.email())), 200);
        long titularRelationship = relationshipIdOf(id, child, child.id());

        // El cuidador ya no es PRINCIPAL, así que no llega ni a intentarlo.
        send(delete(BASE + "/" + id + "/relationships/" + titularRelationship), caregiver, null, 403);
        // Y el propio titular tampoco puede revocarse a sí mismo.
        send(delete(BASE + "/" + id + "/relationships/" + titularRelationship), child, null, 409);
    }

    @Test
    @DisplayName("el titular sí puede revocar a quien le entregó el perfil")
    void theTitularCanRevokeTheOneWhoHandedItOver() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        send(post(EXCHANGE), child, Map.of("token", emitAndReadToken(id, child.email())), 200);
        long caregiverRelationship = relationshipIdOf(id, child, caregiver.id());

        send(delete(BASE + "/" + id + "/relationships/" + caregiverRelationship), child, null, 204);

        send(get(BASE + "/" + id), caregiver, null, 403);
    }

    // --- lo que puede cambiar entre emitir y canjear ---

    @Test
    @DisplayName("el enlace llega al buzón invitado y no vuelve a quien lo emite")
    void theLinkReachesTheInvitedMailboxAndNotTheOneWhoEmitsIt() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        clearInvocations(emailSender);

        JsonNode response = emit(id, child.email(), 201);

        // Quien entrega ve que la invitación existe, pero no la llave.
        assertNull(response.get("token"));
        assertNull(response.get("claimLink"));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(5_000)).send(eq(child.email()), eq(CLAIM_SUBJECT), html.capture());
        assertTrue(TOKEN.matcher(html.getValue()).find());
    }

    @Test
    @DisplayName("una invitación revocada ya no entrega el perfil")
    void aRevokedInvitationNoLongerHandsOverTheProfile() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String token = emitAndReadToken(id, child.email());

        send(delete(BASE + "/" + id + "/claim-invitations/" + invitationIdOf(id)), caregiver, null, 204);

        send(post(EXCHANGE), child, Map.of("token", token), 409);
    }

    @Test
    @DisplayName("si la fecha de nacimiento baja de la edad mínima, el enlace emitido antes no sirve")
    void aBirthDateBelowTheMinimumAgeInvalidatesALinkEmittedBefore() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String token = emitAndReadToken(id, child.email());

        send(patch(BASE + "/" + id), caregiver, Map.of(
                "name", "Adolescente",
                "birthDate", LocalDate.now().minusYears(12).toString()), 200);

        send(post(EXCHANGE), child, Map.of("token", token), 409);
    }

    @Test
    @DisplayName("si el perfil se declara ficticio, el enlace emitido antes no sirve")
    void aProfileDeclaredFictitiousInvalidatesALinkEmittedBefore() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        String token = emitAndReadToken(id, child.email());

        send(post(BASE + "/" + id + "/authorization-declarations"), caregiver, Map.of(
                "type", "FICTITIOUS",
                "textVersion", "1",
                "confirmed", true,
                "previousDeclarationId", latestDeclarationIdOf(id)), 200);

        send(post(EXCHANGE), child, Map.of("token", token), 409);
    }

    @Test
    @DisplayName("una invitación PRINCIPAL pendiente no crea un segundo titular")
    void aPendingPrincipalInvitationDoesNotCreateASecondTitular() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        Session other = register();
        long pending = send(post(BASE + "/" + id + "/relationships"), caregiver,
                Map.of("email", other.email(), "role", "PRINCIPAL"), 201).get("id").asLong();

        send(post(EXCHANGE), child, Map.of("token", emitAndReadToken(id, child.email())), 200);
        send(post("/api/v1/me/relationships/" + pending + "/accept"), other, null, 200);

        JsonNode relationships = send(get(BASE + "/" + id + "/relationships"), child, null, 200);
        // Conserva el acceso, pero el titular sigue siendo el único que decide.
        assertEquals("CAREGIVER", roleOf(relationships, other.id()));
        assertEquals("ACTIVE", statusOf(relationships, other.id()));
        assertEquals("PRINCIPAL", roleOf(relationships, child.id()));
        assertEquals(1, principalCount(relationships));
    }

    @Test
    @DisplayName("un perfil vacío con una invitación revocada se puede borrar")
    void anEmptyProfileWithARevokedInvitationCanBeDeleted() throws Exception {
        long id = createSubject("Adolescente", LocalDate.now().minusYears(17), "LEGAL_REPRESENTATION");
        emitAndReadToken(id, child.email());
        send(delete(BASE + "/" + id + "/claim-invitations/" + invitationIdOf(id)), caregiver, null, 204);

        send(delete(BASE + "/" + id), caregiver, null, 204);

        send(get(BASE + "/" + id), caregiver, null, 403);
    }

    // --- apoyo ---

    private record Session(String token, long id, String email) {}

    private Session register() throws Exception {
        String email = "claim." + UUID.randomUUID() + "@yuyay.app";
        JsonNode auth = send(post("/api/v1/auth/register"), null, Map.of(
                "email", email, "password", "Secreta123", "name", "Prueba",
                "termsAccepted", true, "termsVersion", "1.1"), 201);
        return new Session(auth.get("accessToken").asText(), auth.get("user").get("id").asLong(), email);
    }

    private long createSubject(String name, LocalDate birthDate, String declarationType) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("name", name);
        body.put("declaration", Map.of("type", declarationType, "textVersion", "1", "confirmed", true));
        if (birthDate != null) {
            body.put("birthDate", birthDate.toString());
        }
        return send(post(BASE), caregiver, body, 201).get("id").asLong();
    }

    /**
     * Emite y recupera el token del correo que llega al buzón invitado, que es por donde ahora
     * viaja: la respuesta de la API solo describe la invitación.
     */
    private String emitAndReadToken(long subjectId, String invitedEmail) throws Exception {
        clearInvocations(emailSender);
        emit(subjectId, invitedEmail, 201);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(5_000)).send(eq(invitedEmail), eq(CLAIM_SUBJECT), html.capture());

        Matcher link = TOKEN.matcher(html.getValue());
        assertTrue(link.find(), "El correo de entrega no trae el enlace");
        return link.group(1);
    }

    private long invitationIdOf(long subjectId) throws Exception {
        return send(get(BASE + "/" + subjectId + "/claim-invitations"), caregiver, null, 200)
                .get(0).get("id").asLong();
    }

    private long latestDeclarationIdOf(long subjectId) throws Exception {
        return send(get(BASE + "/" + subjectId + "/authorization-declarations"), caregiver, null, 200)
                .get(0).get("id").asLong();
    }

    private int principalCount(JsonNode relationships) {
        int principals = 0;
        for (JsonNode relationship : relationships) {
            if ("PRINCIPAL".equals(relationship.get("role").asText())
                    && "ACTIVE".equals(relationship.get("status").asText())) {
                principals++;
            }
        }
        return principals;
    }

    private JsonNode emit(long subjectId, String invitedEmail, int expected) throws Exception {
        return send(post(BASE + "/" + subjectId + "/claim-invitations"), caregiver,
                Map.of("invitedEmail", invitedEmail), expected);
    }

    private long relationshipIdOf(long subjectId, Session actor, long userId) throws Exception {
        for (JsonNode relationship : send(get(BASE + "/" + subjectId + "/relationships"), actor, null, 200)) {
            if (relationship.get("user").get("id").asLong() == userId) {
                return relationship.get("id").asLong();
            }
        }
        throw new AssertionError("Sin relación para el usuario " + userId);
    }

    private String roleOf(JsonNode relationships, long userId) {
        return fieldOf(relationships, userId, "role");
    }

    private String statusOf(JsonNode relationships, long userId) {
        return fieldOf(relationships, userId, "status");
    }

    private String fieldOf(JsonNode relationships, long userId, String field) {
        for (JsonNode relationship : relationships) {
            if (relationship.get("user").get("id").asLong() == userId) {
                return relationship.get(field).asText();
            }
        }
        throw new AssertionError("Sin relación para el usuario " + userId);
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
