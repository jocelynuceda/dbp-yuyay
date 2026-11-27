package com.yuyay.auth;

import com.yuyay.auth.service.RegistrationDniQuota;
import com.yuyay.exception.AuthFlowException;
import com.yuyay.identity.IdentityDirectory;
import com.yuyay.identity.IdentityLookup;
import com.yuyay.identity.IdentityRecord;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.registration.dni-per-origin=2", "app.registration.dni-daily-limit=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Registro con DNI contrastado contra el nombre")
class RegisterDniIntegrationTest {

    private static final String DNI = "45678912";
    private static final IdentityRecord ANA = new IdentityRecord("ANA LUCIA", "QUISPE", "MAMANI");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationDniQuota quota;

    @BeforeEach
    void resetQuota() {
        jdbc.update("DELETE FROM registration_dni_quotas");
    }

    @MockitoBean IdentityDirectory identityDirectory;

    @Test
    @DisplayName("el registro con los términos vigentes y sin DNI no consume cuota")
    void missingDniKeepsTheLegacyContract() throws Exception {
        String email = email();
        register(email, "Ana Quispe", null).andExpect(status().isCreated());
        assertTrue(users.existsByEmailIgnoreCase(email));
        verify(identityDirectory, never()).findByDni(any());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM registration_dni_quotas", Integer.class));
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
        when(identityDirectory.findByDni(DNI)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new IdentityLookup.Found(ANA);
        });
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
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Revisa el DNI y escribe al menos un nombre y un apellido tal como figuran en tu documento."));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("un DNI que no existe: 400 en el campo dni y no se crea la cuenta")
    void unknownDniIsRejected() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.NotFound());
        String email = email();
        register(email, "Ana Quispe", DNI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')].message").value("Revisa el DNI y escribe al menos un nombre y un apellido tal como figuran en tu documento."));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    void aNearMatchCanBeCorrectedBeforeAnAccountIsCreated() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(
                new IdentityLookup.Found(new IdentityRecord("LUIS FELIPE", "RAMOS", "SOTO")));
        String email = email();
        register(email, "Luiz Ramos", DNI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dni"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Revisa el DNI y escribe al menos un nombre y un apellido tal como figuran en tu documento."));
        assertFalse(users.existsByEmailIgnoreCase(email));
        register(email, "Luis Ramos", DNI).andExpect(status().isCreated());
        assertTrue(users.existsByEmailIgnoreCase(email));
    }

    @Test
    void unavailableDirectoryIsRetryableAndDoesNotCreateAnAccount() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Unavailable("sin respuesta"));
        String email = email();
        register(email, "Ana Quispe", DNI).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DNI_LOOKUP_UNAVAILABLE"));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    void duplicateEmailDoesNotSpendALookup() throws Exception {
        String email = email();
        register(email, "Ana Quispe", null).andExpect(status().isCreated());
        register(email, "Ana Quispe", DNI).andExpect(status().isConflict());
        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    void originAndGlobalLimitsApplyBeforeLookup() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.NotFound());
        register(email(), "Ana Quispe", DNI).andExpect(status().isBadRequest());
        register(email(), "Ana Quispe", DNI).andExpect(status().isBadRequest());
        register(email(), "Ana Quispe", DNI).andExpect(status().isTooManyRequests());
        // El rechazo anterior no consume la última consulta del presupuesto global.
        register(email(), "Ana Quispe", DNI, "192.0.2.2").andExpect(status().isBadRequest());
        register(email(), "Ana Quispe", DNI, "192.0.2.3").andExpect(status().isTooManyRequests());
        verify(identityDirectory, times(3)).findByDni(DNI);
        register(email(), "Ana Quispe", null).andExpect(status().isCreated());
    }

    @Test
    void concurrentReservationsShareOneOriginBudget() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(workers.submit(() -> {
                    start.await();
                    try {
                        quota.reserve("192.0.2.4");
                        return true;
                    } catch (AuthFlowException limited) {
                        assertEquals("DNI_LOOKUP_LIMIT", limited.getCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var call : calls) {
                if (call.get(10, TimeUnit.SECONDS)) allowed++;
            }
            assertEquals(2, allowed);
        }
    }

    @Test
    void cleanupDropsOnlyExpiredBuckets() {
        quota.reserve("192.0.2.4");
        jdbc.update("INSERT INTO registration_dni_quotas (bucket, quota_day, used_count) VALUES (?, ?, ?)",
                "old-key", LocalDate.now(ZoneOffset.UTC).minusDays(1), 1);
        quota.deleteExpired();
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM registration_dni_quotas WHERE bucket = 'old-key'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM registration_dni_quotas", Integer.class));
    }

    private ResultActions register(String email, String name, String dni) throws Exception {
        return register(email, name, dni, "192.0.2.1");
    }

    private ResultActions register(String email, String name, String dni, String origin) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "email", email, "password", "Secreta123", "name", name,
                "termsAccepted", true, "termsVersion", "1.1"));
        if (dni != null) body.put("dni", dni);
        return mvc.perform(post("/api/v1/auth/register")
                .with(req -> {
                    req.setRemoteAddr(origin);
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static String email() {
        return "dni." + UUID.randomUUID() + "@yuyay.app";
    }
}
