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
import java.time.ZoneId;
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
    private static final IdentityRecord ANA = new IdentityRecord("ANA LUCIA", "QUISPE", "MAMANI", LocalDate.of(1990, 1, 1));

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

    @BeforeEach
    void configuredDirectory() {
        when(identityDirectory.isConfigured()).thenReturn(true);
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Found(ANA));
    }

    @Test
    @DisplayName("sin DNI no se crea la cuenta ni se consume cuota")
    void missingDniIsRejected() throws Exception {
        String email = email();
        // Una APK anterior omite ambos campos e indica al usuario que debe actualizar.
        register(email, "Anita", null, "192.0.2.1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dni"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Ingresa tu DNI. Si no ves el campo, actualiza Yuyay."));
        assertFalse(users.existsByEmailIgnoreCase(email));
        verify(identityDirectory, never()).findByDni(any());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM registration_dni_quotas", Integer.class));
    }

    @Test
    @DisplayName("un DNI que no tiene ocho dígitos se rechaza sin consultar")
    void malformedDniIsAFieldError() throws Exception {
        register(email(), "Anita", "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')].message").value("El DNI debe tener ocho dígitos"));
        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("la ruta pública de autocompletado ya no existe")
    void anonymousFullNameLookupIsAbsent() throws Exception {
        lookup(DNI).andExpect(status().isNotFound());
        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    @DisplayName("se guarda el nombre de usuario y, aparte, el nombre declarado contrastado")
    void accountKeepsUsernameAndDeclaredName() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new IdentityLookup.Found(ANA);
        });
        String email = email();
        register(email, "Anita", DNI)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.name").value("Anita"))
                .andExpect(jsonPath("$.user.fullName").value("Ana Quispe"))
                .andExpect(jsonPath("$.user.dni").doesNotExist());
        assertEquals("Ana Quispe", users.findByEmailIgnoreCase(email).orElseThrow().getFullName());
    }

    @Test
    @DisplayName("un DNI que no existe: 400 en el campo dni y no se crea la cuenta")
    void unknownDniIsRejected() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.NotFound());
        String email = email();
        register(email, "Anita", DNI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'dni')].message").value("Revisa el DNI y escribe tus nombres y apellidos tal como figuran en tu documento."));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    void unavailableDirectoryIsRetryableAndDoesNotCreateAnAccount() throws Exception {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Unavailable("sin respuesta"));
        String email = email();
        register(email, "Anita", DNI).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DNI_LOOKUP_UNAVAILABLE"));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    void unconfiguredDirectoryRejectsRegistrationBeforeSpendingQuota() throws Exception {
        when(identityDirectory.isConfigured()).thenReturn(false);
        String email = email();
        register(email, "Anita", DNI).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DNI_LOOKUP_UNAVAILABLE"));
        assertFalse(users.existsByEmailIgnoreCase(email));
        verify(identityDirectory, never()).findByDni(any());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM registration_dni_quotas", Integer.class));
    }

    @Test
    void wrongDeclaredNameCanBeCorrectedWithoutCreatingAnAccountOrRevealingTheOfficialName() throws Exception {
        String email = email();
        // El alias coincide con el documento pero solo se debe contrastar el nombre declarado.
        register(email, "Ana Quispe", DNI, "192.0.2.1", "Ana Flores")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dni"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Revisa el DNI y escribe tus nombres y apellidos tal como figuran en tu documento."));
        assertFalse(users.existsByEmailIgnoreCase(email));
        register(email, "Anita", DNI, "192.0.2.1", "Ana Quispe").andExpect(status().isCreated());
        verify(identityDirectory, times(2)).findByDni(DNI);
    }

    @Test
    void missingDeclaredNameDoesNotSpendALookup() throws Exception {
        register(email(), "Ana Quispe", DNI, "192.0.2.1", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fullName"));
        verify(identityDirectory, never()).findByDni(any());
    }

    @Test
    void duplicateEmailDoesNotSpendALookup() throws Exception {
        String email = email();
        register(email, "Anita", DNI).andExpect(status().isCreated());
        register(email, "Anita", DNI).andExpect(status().isConflict());
        verify(identityDirectory, times(1)).findByDni(any());
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
        register(email(), "Ana Quispe", null).andExpect(status().isBadRequest());
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

    @Test
    @DisplayName("quien cumple 16 años hoy puede registrarse")
    void sixteenthBirthdayIsAllowed() throws Exception {
        bornOn(todayInLima().minusYears(16));
        register(email(), "Anita", DNI).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("con 15 años no se crea la cuenta")
    void underSixteenIsRejected() throws Exception {
        bornOn(todayInLima().minusYears(16).plusDays(1));
        String email = email();
        register(email, "Anita", DNI).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dni"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Debes tener 16 años o más para crear una cuenta."));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    @Test
    @DisplayName("la edad no se revela si el nombre no coincide")
    void ageIsNotRevealedWhenTheNameDoesNotMatch() throws Exception {
        bornOn(todayInLima().minusYears(10));
        register(email(), "Anita", DNI, "192.0.2.1", "Otra Persona").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Revisa el DNI y escribe tus nombres y apellidos tal como figuran en tu documento."));
    }

    @Test
    @DisplayName("sin fecha de nacimiento no se puede comprobar la edad: 503")
    void missingBirthDateDoesNotCreateAnAccount() throws Exception {
        bornOn(null);
        String email = email();
        register(email, "Anita", DNI).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DNI_LOOKUP_UNAVAILABLE"));
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    private void bornOn(LocalDate birthDate) {
        when(identityDirectory.findByDni(DNI)).thenReturn(new IdentityLookup.Found(
                new IdentityRecord("ANA LUCIA", "QUISPE", "MAMANI", birthDate)));
    }

    private static LocalDate todayInLima() {
        return LocalDate.now(ZoneId.of("America/Lima"));
    }

    private ResultActions register(String email, String name, String dni) throws Exception {
        return register(email, name, dni, "192.0.2.1");
    }

    private ResultActions lookup(String dni) throws Exception {
        return mvc.perform(post("/api/v1/auth/dni-lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("dni", dni))));
    }

    private ResultActions register(String email, String name, String dni, String origin) throws Exception {
        return register(email, name, dni, origin, "Ana Quispe");
    }

    private ResultActions register(String email, String name, String dni, String origin, String fullName) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "email", email, "password", "Secreta123", "name", name,
                "termsAccepted", true, "termsVersion", "1.3"));
        if (dni != null) body.put("dni", dni);
        if (fullName != null) body.put("fullName", fullName);
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
