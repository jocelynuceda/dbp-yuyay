package com.yuyay.auth.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yuyay.event.UserRegisteredEvent;
import com.yuyay.identity.IdentityDirectory;
import com.yuyay.identity.IdentityLookup;
import com.yuyay.identity.IdentityRecord;
import com.yuyay.notification.email.EmailSender;
import com.yuyay.user.entity.Role;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.PasswordResetCodeRepository;
import com.yuyay.user.repository.RefreshTokenRepository;
import com.yuyay.user.repository.UserRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.registration.dni-per-origin=3")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class GoogleAuthIntegrationTest {
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final String WEB_CLIENT = "test-web.apps.googleusercontent.com";
    private static final String SECOND_CLIENT = "test-second.apps.googleusercontent.com";
    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");
    private static final RSAKey KEY = generateKey();
    private static final RSAKey OTHER_KEY = generateKey();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordResetCodeRepository resetCodes;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommittedRegistrations committedRegistrations;
    @MockitoBean IdentityDirectory directory;
    @MockitoBean EmailSender emailSender;

    @BeforeEach
    void prepareDirectory() {
        jdbc.update("DELETE FROM registration_dni_quotas");
        when(directory.isConfigured()).thenReturn(true);
        when(directory.findByDni("12345678")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new IdentityLookup.Found(new IdentityRecord("PERSONA", "SINTETICA", "PEREZ"));
        });
    }

    @TestConfiguration
    static class TestKeys {
        @Bean
        CommittedRegistrations committedRegistrations() {
            return new CommittedRegistrations();
        }

        @Bean @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean @Primary
        GoogleIdTokenVerifier testVerifier(Clock clock, Validator validator) throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            return new GoogleIdTokenVerifier(WEB_CLIENT + ", " + SECOND_CLIENT, clock, validator, decoder);
        }
    }

    static class CommittedRegistrations {
        private final List<Long> userIds = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void record(UserRegisteredEvent event) {
            userIds.add(event.userId());
        }
    }

    @Test
    void rejectsBadSignatureMalformedTokenAndInvalidClaims() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = uniqueEmail();
        String[] invalid = {
                "malformed",
                signedToken(OTHER_KEY, subject, email, builder -> builder),
                token(subject, email, builder -> builder.issuer("https://example.invalid")),
                token(subject, email, builder -> builder.audience("other.apps.googleusercontent.com")),
                token(subject, email, builder -> builder.expirationTime(null)),
                token(subject, email, builder -> builder.expirationTime(Date.from(NOW))),
                token(subject, email, builder -> builder.expirationTime(Date.from(NOW.minusSeconds(1)))),
                token(subject, email, builder -> builder.notBeforeTime(Date.from(NOW.plusSeconds(30)))),
                token(subject, email, builder -> builder.claim("email_verified", false)),
                token(subject, email, builder -> builder.claim("email_verified", "true")),
                token(subject, email, builder -> builder.subject(" ")),
                token(subject, "malformed", builder -> builder)
        };
        for (String idToken : invalid) {
            JsonNode error = request("/google", Map.of("idToken", idToken,
                    "acceptedTermsVersion", "1.2"), 401);
            assertEquals("GOOGLE_TOKEN_INVALID", error.path("code").asString());
        }
        JsonNode invalidToken = request("/google", Map.of("idToken", "malformed", "acceptedTermsVersion", true), 401);
        assertEquals("GOOGLE_TOKEN_INVALID", invalidToken.path("code").asString());
        assertTrue(users.findByGoogleSubject(subject).isEmpty());
        verifyNoInteractions(directory);
    }

    @Test
    void newGoogleAccountRequiresAcceptanceAndUsesTheSameIdentityOnReturn() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = uniqueEmail();
        String idToken = token(subject, email, builder -> builder.issuer("accounts.google.com"));
        long sessionsBefore = refreshTokens.count();
        assertTermsChallenge(Map.of("idToken", idToken), "TERMS_ACCEPTANCE_REQUIRED");
        assertTermsChallenge(Map.of("idToken", idToken, "acceptedTermsVersion", NullNode.getInstance()),
                "TERMS_ACCEPTANCE_REQUIRED");
        assertTermsChallenge(Map.of("idToken", idToken, "termsAccepted", true, "termsVersion", "1.2", "dni", "12345678"),
                "TERMS_ACCEPTANCE_REQUIRED");
        assertTermsChallenge(Map.of("idToken", idToken, "acceptedTermsVersion", "2026-11"),
                "TERMS_VERSION_OUTDATED");
        assertTermsChallenge(Map.of("idToken", idToken, "acceptedTermsVersion", "1.2"),
                "REGISTRATION_DETAILS_REQUIRED");
        verifyNoInteractions(directory);
        assertTrue(users.findByGoogleSubject(subject).isEmpty());
        assertTrue(users.findByEmailIgnoreCase(email).isEmpty());
        assertEquals(sessionsBefore, refreshTokens.count());

        JsonNode created = signUp(idToken);
        User saved = users.findByGoogleSubject(subject).orElseThrow();
        assertEquals(saved.getId().longValue(), created.path("user").path("id").asLong());
        assertEquals("USER", created.path("user").path("role").asString());
        assertFalse(created.path("user").has("googleSubject"));
        assertFalse(created.path("user").has("passwordHash"));
        assertNull(saved.getPasswordHash());
        assertEquals("Alias", saved.getName());
        assertEquals("Persona Sintética", saved.getFullName());
        assertEquals("1.2", saved.getTermsVersion());
        assertEquals(NOW, saved.getTermsAcceptedAt());

        String changedEmail = uniqueEmail();
        JsonNode returned = request("/google", Map.of("idToken", token(subject, changedEmail,
                builder -> builder.audience(SECOND_CLIENT))), 200);
        assertEquals(saved.getId().longValue(), returned.path("user").path("id").asLong());
        assertEquals(email, returned.path("user").path("email").asString());
        assertTrue(users.findByEmailIgnoreCase(changedEmail).isEmpty());
        assertEquals(NOW, users.findByGoogleSubject(subject).orElseThrow().getTermsAcceptedAt());
        JsonNode withOldVersion = request("/google", Map.of("idToken", idToken, "acceptedTermsVersion", "0.9"), 200);
        assertEquals(saved.getId().longValue(), withOldVersion.path("user").path("id").asLong());
        verify(directory, times(1)).findByDni("12345678");

        mvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + created.path("accessToken").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        Map<String, String> firstRefresh = Map.of("refreshToken", created.path("refreshToken").asString());
        JsonNode rotated = request("/refresh", firstRefresh, 200);
        request("/refresh", firstRefresh, 401);
        Map<String, String> nextRefresh = Map.of("refreshToken", rotated.path("refreshToken").asString());
        request("/logout", nextRefresh, 204);
        request("/refresh", nextRefresh, 401);
    }

    @Test
    void rejectsMalformedAcceptanceVersionWithoutCreatingAccountOrSession() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = uniqueEmail();
        String idToken = token(subject, email, builder -> builder);
        long sessionsBefore = refreshTokens.count();
        for (Object invalid : List.of(1.0, true, Map.of("version", "1.0"), "", " ", " 1.0", "x".repeat(33))) {
            JsonNode error = request("/google", Map.of("idToken", idToken, "acceptedTermsVersion", invalid), 400);
            assertEquals("TERMS_VERSION_INVALID", error.path("code").asString());
            assertFalse(error.has("currentVersion"));
            assertFalse(error.has("accessToken"));
            assertFalse(error.has("refreshToken"));
        }
        assertTrue(users.findByGoogleSubject(subject).isEmpty());
        assertEquals(sessionsBefore, refreshTokens.count());
    }

    @Test
    void googleDoesNotLinkAnExistingPasswordAccountOrAnotherGoogleSubject() throws Exception {
        String localEmail = uniqueEmail();
        String password = "Original123";
        request("/register", Map.of("email", localEmail, "password", password, "name", "Local",
                "termsAccepted", true, "termsVersion", "1.2", "dni", "12345678", "fullName", "Persona Sintetica"), 201);
        String unlinkedSubject = UUID.randomUUID().toString();
        String localToken = token(unlinkedSubject, localEmail.toUpperCase(java.util.Locale.ROOT), builder -> builder);
        JsonNode conflict = request("/google", Map.of("idToken", localToken), 409);
        assertEquals("GOOGLE_EMAIL_CONFLICT", conflict.path("code").asString());
        assertTrue(conflict.path("message").asString().contains("contraseña"));
        assertFalse(conflict.has("currentVersion"));
        assertTrue(users.findByGoogleSubject(unlinkedSubject).isEmpty());
        request("/login", Map.of("email", localEmail, "password", password), 200);

        String googleEmail = uniqueEmail();
        signUp(token(UUID.randomUUID().toString(), googleEmail, builder -> builder));
        String otherSubject = UUID.randomUUID().toString();
        JsonNode googleConflict = request("/google", Map.of("idToken", token(otherSubject, googleEmail,
                builder -> builder)), 409);
        assertEquals("GOOGLE_EMAIL_CONFLICT", googleConflict.path("code").asString());
        assertTrue(users.findByGoogleSubject(otherSubject).isEmpty());
    }

    @Test
    void existingGoogleAccountWithoutDeclaredNameCanSignInWhileTheDirectoryIsDown() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = uniqueEmail();
        User legacy = users.save(User.builder().email(email).name("Google anterior")
                .googleSubject(subject).role(Role.USER).build());
        when(directory.isConfigured()).thenReturn(false);
        JsonNode session = request("/google", Map.of("idToken", token(subject, email, builder -> builder)), 200);
        assertEquals(legacy.getId().longValue(), session.path("user").path("id").asLong());
        verifyNoInteractions(directory);
    }

    @Test
    void googleAndPasswordShareTheLookupBudgetAndHandleFailuresWithoutIssuingSessions() throws Exception {
        String subject = UUID.randomUUID().toString();
        String idToken = token(subject, uniqueEmail(), builder -> builder);
        long before = refreshTokens.count();
        Map<String, String> wrongName = Map.of("idToken", idToken, "acceptedTermsVersion", "1.2",
                "dni", "12345678", "fullName", "Otra Persona", "name", "Alias");
        JsonNode malformed = request("/google", Map.of("idToken", idToken, "acceptedTermsVersion", "1.2",
                "dni", "123", "fullName", "Persona Sintética", "name", "Alias"), 400);
        assertEquals("dni", malformed.path("fieldErrors").get(0).path("field").asString());
        verifyNoInteractions(directory);
        JsonNode mismatch = request("/google", wrongName, 400);
        assertEquals("dni", mismatch.path("fieldErrors").get(0).path("field").asString());
        when(directory.findByDni("12345678")).thenReturn(new IdentityLookup.NotFound());
        JsonNode absent = request("/google", wrongName, 400);
        assertEquals(mismatch.path("fieldErrors"), absent.path("fieldErrors"));
        when(directory.findByDni("12345678")).thenReturn(new IdentityLookup.Unavailable("temporal"));
        JsonNode unavailable = request("/google", wrongName, 503);
        assertEquals("DNI_LOOKUP_UNAVAILABLE", unavailable.path("code").asString());
        JsonNode limit = request("/register", Map.of("email", uniqueEmail(), "password", "Original123",
                "name", "Alias", "termsAccepted", true, "termsVersion", "1.2", "dni", "12345678",
                "fullName", "Persona Sintética"), 429);
        assertEquals("DNI_LOOKUP_LIMIT", limit.path("code").asString());
        assertTrue(users.findByGoogleSubject(subject).isEmpty());
        assertEquals(before, refreshTokens.count());
        verify(directory, times(3)).findByDni("12345678");
    }

    @Test
    void googleAccountsCannotSignInOrResetWithPassword() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = uniqueEmail();
        signUp(token(subject, email, builder -> builder));
        User saved = users.findByGoogleSubject(subject).orElseThrow();
        request("/login", Map.of("email", email, "password", "AnyPassword123"), 401);
        request("/forgot-password", Map.of("email", email), 204);
        Boolean codeMissing = new TransactionTemplate(transactionManager).execute(
                tx -> resetCodes.findByUserId(saved.getId()).isEmpty());
        assertEquals(Boolean.TRUE, codeMissing);
        request("/reset-password", Map.of("email", email, "code", "123456", "newPassword", "AnyPassword123"), 409);
        assertNull(users.findByGoogleSubject(subject).orElseThrow().getPasswordHash());
    }

    @Test
    void concurrentRequestsWithTheSameSubjectShareOneAccountEvenAcrossEmailChanges() throws Exception {
        String subject = UUID.randomUUID().toString();
        String firstEmail = uniqueEmail();
        String secondEmail = uniqueEmail();
        String firstToken = token(subject, firstEmail, builder -> builder);
        String secondToken = token(subject, secondEmail, builder -> builder);
        var pendingLookups = new CountDownLatch(2);
        when(directory.findByDni("12345678")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            pendingLookups.countDown();
            assertTrue(pendingLookups.await(5, TimeUnit.SECONDS), "Ambas altas deben solaparse en la consulta externa");
            return new IdentityLookup.Found(new IdentityRecord("PERSONA", "SINTETICA", ""));
        });
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return signUp(firstToken);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return signUp(secondToken);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            JsonNode firstAuth = first.get(10, TimeUnit.SECONDS);
            JsonNode secondAuth = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstAuth.path("user").path("id"), secondAuth.path("user").path("id"));
            assertFalse(firstAuth.path("refreshToken").equals(secondAuth.path("refreshToken")),
                    "Concurrent sessions must have distinct refresh tokens");
        }
        User saved = users.findByGoogleSubject(subject).orElseThrow();
        assertEquals(NOW, saved.getTermsAcceptedAt());
        assertTrue(users.findByEmailIgnoreCase(saved.getEmail().equals(firstEmail) ? secondEmail : firstEmail).isEmpty());
        assertEquals(1L, committedRegistrations.userIds.stream().filter(saved.getId()::equals).count());
        verify(emailSender, timeout(10000).times(1)).send(eq(saved.getEmail()), eq("Te damos la bienvenida a Yuyay"), any());
    }

    private JsonNode signUp(String idToken) throws Exception {
        return request("/google", Map.of("idToken", idToken, "acceptedTermsVersion", "1.2",
                "dni", "12345678", "fullName", "Persona Sintética", "name", "Alias"), 200);
    }

    private void assertTermsChallenge(Map<String, ?> body, String code) throws Exception {
        MockHttpServletResponse response = performRequest("/google", body);
        assertEquals(428, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        JsonNode error = json.readTree(response.getContentAsByteArray());
        assertEquals(428, error.path("status").asInt());
        assertEquals(code, error.path("code").asString());
        assertEquals("1.2", error.path("currentVersion").asString());
        assertFalse(error.has("accessToken"));
        assertFalse(error.has("refreshToken"));
    }

    private JsonNode request(String path, Map<String, ?> body, int expected) throws Exception {
        MockHttpServletResponse response = performRequest(path, body);
        assertEquals(expected, response.getStatus(), "Status at " + path);
        byte[] content = response.getContentAsByteArray();
        return content.length == 0 ? null : json.readTree(content);
    }

    private MockHttpServletResponse performRequest(String path, Map<String, ?> body) throws Exception {
        return mvc.perform(post("/api/v1/auth" + path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn().getResponse();
    }

    private String token(String subject, String email, UnaryOperator<JWTClaimsSet.Builder> custom) throws JOSEException {
        return signedToken(KEY, subject, email, custom);
    }

    private String signedToken(RSAKey key, String subject, String email, UnaryOperator<JWTClaimsSet.Builder> custom)
            throws JOSEException {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(GOOGLE_ISSUER).audience(WEB_CLIENT)
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .claim("email", email).claim("email_verified", true).claim("name", "Persona de prueba");
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), custom.apply(claims).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (JOSEException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String uniqueEmail() {
        return "google." + UUID.randomUUID() + "@example.invalid";
    }
}
