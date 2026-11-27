package com.yuyay.care;

import com.yuyay.identity.IdentityDirectory;
import com.yuyay.identity.IdentityLookup;
import com.yuyay.identity.IdentityRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Contraste del DNI contra el nombre de un perfil")
class CareSubjectIdentityCheckIntegrationTest {

    private static final String BASE = "/api/v1/care-subjects";
    private static final String DNI = "12345678";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean IdentityDirectory identityDirectory;

    private Session owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
    }

    @Test
    @DisplayName("sin sesión no se consulta nada")
    void withoutASessionNothingIsQueried() throws Exception {
        mvc.perform(post(BASE + "/1/identity-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("dni", DNI))))
                .andExpect(status().isUnauthorized());

        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("el nombre del DNI coincide con el del perfil")
    void theNameOnTheDocumentMatchesTheProfile() throws Exception {
        long id = createSubject("Ana Quispe", "HOLDER_PERMISSION");
        when(identityDirectory.findByDni(DNI)).thenReturn(
                new IdentityLookup.Found(new IdentityRecord("ANA MARIA", "QUISPE", "ROJAS")));

        assertEquals("MATCH", check(id, DNI, 200).get("result").asText());
    }

    @Test
    @DisplayName("un nombre distinto devuelve MISMATCH, no un error")
    void aDifferentNameIsAMismatchAndNotAnError() throws Exception {
        long id = createSubject("Rosa Flores", "HOLDER_PERMISSION");
        when(identityDirectory.findByDni(DNI)).thenReturn(
                new IdentityLookup.Found(new IdentityRecord("ANA MARIA", "QUISPE", "ROJAS")));

        assertEquals("MISMATCH", check(id, DNI, 200).get("result").asText());
    }

    @Test
    @DisplayName("un DNI que no existe devuelve NOT_FOUND")
    void anUnknownDocumentIsNotFound() throws Exception {
        long id = createSubject("Ana Quispe", "HOLDER_PERMISSION");
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.NotFound());

        assertEquals("NOT_FOUND", check(id, DNI, 200).get("result").asText());
    }

    @Test
    @DisplayName("un proveedor caído deja el contraste pendiente, sin bloquear")
    void aProviderThatIsDownLeavesTheCheckPending() throws Exception {
        long id = createSubject("Ana Quispe", "HOLDER_PERMISSION");
        when(identityDirectory.findByDni(DNI))
                .thenReturn(new IdentityLookup.Unavailable("El servicio de consulta no respondió"));

        assertEquals("UNAVAILABLE", check(id, DNI, 200).get("result").asText());
    }

    @Test
    @DisplayName("la respuesta nunca devuelve el nombre del DNI")
    void theResponseNeverCarriesTheNameFromTheDocument() throws Exception {
        long id = createSubject("Rosa Flores", "HOLDER_PERMISSION");
        when(identityDirectory.findByDni(DNI)).thenReturn(
                new IdentityLookup.Found(new IdentityRecord("ANA MARIA", "QUISPE", "ROJAS")));

        String body = mvc.perform(authenticated(post(BASE + "/" + id + "/identity-check"), Map.of("dni", DNI)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Si devolviera el nombre, el endpoint sería un buscador de personas por DNI.
        assertFalse(body.toUpperCase().contains("QUISPE"));
        assertFalse(body.toUpperCase().contains("ANA"));
    }

    @Test
    @DisplayName("un DNI mal formado no gasta consulta")
    void aMalformedDocumentSpendsNoQuery() throws Exception {
        long id = createSubject("Ana Quispe", "HOLDER_PERMISSION");

        check(id, "1234", 400);
        check(id, "1234567a", 400);

        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("un perfil ficticio no se contrasta con un documento real")
    void aFictitiousProfileIsNotCheckedAgainstARealDocument() throws Exception {
        long id = createSubject("Persona inventada", "FICTITIOUS");

        check(id, DNI, 409);

        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("quien no es PRINCIPAL no puede contrastar ni gastar consulta")
    void someoneWhoIsNotPrincipalCannotCheck() throws Exception {
        long id = createSubject("Ana Quispe", "HOLDER_PERMISSION");
        Session stranger = register();

        mvc.perform(post(BASE + "/" + id + "/identity-check")
                        .header("Authorization", "Bearer " + stranger.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("dni", DNI))))
                .andExpect(status().isForbidden());

        verify(identityDirectory, never()).findByDni(any());
    }

    // --- apoyo ---

    private record Session(String token, long id) {}

    private Session register() throws Exception {
        JsonNode auth = send(post("/api/v1/auth/register"), null, Map.of(
                "email", "identity." + UUID.randomUUID() + "@yuyay.app",
                "password", "Secreta123", "name", "Prueba",
                "termsAccepted", true, "termsVersion", "1.0"), 201);
        return new Session(auth.get("accessToken").asText(), auth.get("user").get("id").asLong());
    }

    private long createSubject(String name, String declarationType) throws Exception {
        return send(post(BASE), owner, Map.of(
                "requestId", UUID.randomUUID().toString(),
                "name", name,
                "declaration", Map.of("type", declarationType, "textVersion", "1", "confirmed", true)),
                201).get("id").asLong();
    }

    private JsonNode check(long id, String dni, int expected) throws Exception {
        return send(post(BASE + "/" + id + "/identity-check"), owner, Map.of("dni", dni), expected);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder req, Object body)
            throws Exception {
        return req.header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
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
