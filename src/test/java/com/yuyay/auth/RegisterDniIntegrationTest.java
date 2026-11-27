package com.yuyay.auth;

import com.yuyay.identity.IdentityDirectory;
import com.yuyay.identity.IdentityLookup;
import com.yuyay.identity.IdentityRecord;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Registro con DNI contrastado contra el nombre")
class RegisterDniIntegrationTest {

    private static final String DNI = "45678912";
    private static final IdentityRecord ANA = new IdentityRecord("ANA LUCIA", "QUISPE", "MAMANI");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;

    @MockitoBean IdentityDirectory identityDirectory;

    @Test
    @DisplayName("sin DNI el registro se rechaza en el campo dni y no se consulta nada")
    void missingDniIsAFieldError() throws Exception {
        String email = email();
        register(email, "Ana Quispe", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')]").exists());
        assertFalse(users.existsByEmailIgnoreCase(email));
        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("un DNI que no tiene ocho dígitos se rechaza sin consultar")
    void malformedDniIsAFieldError() throws Exception {
        register(email(), "Ana Quispe", "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')].message").value("El DNI debe tener ocho dígitos"));
        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("el nombre coincide con el del DNI: se crea la cuenta y el DNI no se devuelve")
    void matchingNameCreatesTheAccount() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Found(ANA));
        String email = email();
        register(email, "Ana Quispe", DNI)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.dni").doesNotExist());
        assertTrue(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("el nombre no coincide: 400 en el campo dni y no se crea la cuenta")
    void mismatchingNameIsRejected() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Found(ANA));
        String email = email();
        register(email, "Rosa Flores", DNI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')]").exists());
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("un DNI que no existe: 400 en el campo dni y no se crea la cuenta")
    void unknownDniIsRejected() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.NotFound());
        String email = email();
        register(email, "Ana Quispe", DNI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')].message").value("DNI no existe, ingresa uno válido"));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("si el directorio no responde: 400 «Tiempo de espera superado» y no se crea la cuenta")
    void unresponsiveDirectoryIsRejected() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Unavailable("sin respuesta", true));
        String email = email();
        register(email, "Ana Quispe", DNI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')].message").value("Tiempo de espera superado"));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("sin token o con el proveedor respondiendo error, el registro sigue")
    void unavailableDirectoryDoesNotBlockSignup() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Unavailable("caído"));
        String email = email();
        register(email, "Ana Quispe", DNI).andExpect(status().isCreated());
        assertTrue(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("un email ya registrado se rechaza antes de gastar una consulta de DNI")
    void duplicateEmailDoesNotSpendALookup() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Unavailable("caído"));
        String email = email();
        register(email, "Ana Quispe", DNI).andExpect(status().isCreated());
        when(identityDirectory.findByDni(DNI)).thenThrow(new AssertionError("no debía consultarse"));
        register(email, "Ana Quispe", DNI).andExpect(status().isConflict());
    }

    private ResultActions register(String email, String name, String dni) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "email", email, "password", "Secreta123", "name", name,
                "termsAccepted", true, "termsVersion", "1.0"));
        if (dni != null) body.put("dni", dni);
        return mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static String email() {
        return "dni." + UUID.randomUUID() + "@yuyay.app";
    }
}
