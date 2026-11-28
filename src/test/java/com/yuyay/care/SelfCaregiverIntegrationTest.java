package com.yuyay.care;

import java.time.LocalDate;
import java.time.Instant;
import com.yuyay.common.Gender;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yuyay.security.TokenHasher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.yuyay.auth.service.TermsService;
import com.yuyay.care.entity.*;
import com.yuyay.care.repository.*;
import com.yuyay.care.service.CareSubjectService;
import com.yuyay.notification.email.EmailSender;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SelfCaregiverIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CareSubjectRepository subjects;
    @Autowired CareRelationshipRepository relationships;
    @Autowired ClaimInvitationRepository invitations;
    @Autowired CareSubjectService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired TermsService terms;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailSender mail;

    record Account(long id, String email, String token) {}

    @Test
    void registrationProvidesOneOwnedPrincipalProfileWithoutInventedDataOrConsent() throws Exception {
        Account account = register();
        JsonNode list = send(get("/api/v1/care-subjects"), account, null, 200);
        assertEquals(1, list.size());
        JsonNode own = list.get(0);
        assertEquals("Alias ficticio", own.path("name").asString());
        assertEquals("PRINCIPAL", own.path("myRole").asString());
        var expectedBirthDate = users.findById(account.id()).orElseThrow().getBirthDate();
        assertEquals(LocalDate.of(1990, 1, 1), expectedBirthDate);
        assertTrue(own.path("ownProfile").asBoolean());
        assertEquals(expectedBirthDate == null ? null : expectedBirthDate.toString(),
                own.path("birthDate").isNull() ? null : own.path("birthDate").asString());
        assertTrue(own.path("notes").isNull());
        CareSubject stored = subjects.findByAccountUserId(account.id()).orElseThrow();
        assertEquals(own.path("id").asLong(), stored.getId());
        assertTrue(stored.isRegistrationProfile());
        assertEquals(CareStatus.ACTIVE, relationships.findByUserIdAndCareSubjectId(account.id(), stored.getId()).orElseThrow().getStatus());
        // La aceptación de términos no se convierte en una declaración SELF no confirmada.
        assertEquals(0, send(get("/api/v1/care-subjects/" + stored.getId() + "/authorization-declarations"), account, null, 200).size());
        send(post("/api/v1/care-subjects/" + stored.getId() + "/health-entries"), account,
                Map.of("categoryCode", "ALLERGY", "title", "Registro ficticio", "confidenceLevel", "CONFIRMED"), 201);
    }

    @Test
    void loginDuplicateRegistrationAndRepeatedProvisioningKeepTheSameProfile() throws Exception {
        Account account = register();
        long own = subjects.findByAccountUserId(account.id()).orElseThrow().getId();
        send(post("/api/v1/auth/login"), null, Map.of("email", account.email(), "password", "Ficticia123"), 200);
        send(post("/api/v1/auth/register"), null, registration(account.email()), 409);
        new TransactionTemplate(manager).executeWithoutResult(tx -> assertEquals(own, service.ensureSelfProfile(account.id()).getId()));
        assertEquals(1, send(get("/api/v1/care-subjects"), account, null, 200).size());
        assertEquals(1, relationships.findByCareSubjectId(own).size());
        send(post("/api/v1/care-subjects"), account, person("SELF"), 409);
        assertEquals(own, subjects.findByAccountUserId(account.id()).orElseThrow().getId());
    }

    @Test
    void simultaneousProvisioningSerializesOnTheRealUserAndCreatesOneRelationship() throws Exception {
        User legacy = users.save(User.builder().email(email()).name("Cuenta antigua ficticia").build());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> {
                start.await();
                return new TransactionTemplate(manager).execute(tx -> service.ensureSelfProfile(legacy.getId()).getId());
            }));
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var task : tasks) ids.add(task.get(15, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, relationships.findByCareSubjectId(ids.iterator().next()).size());
        }
    }

    @Test
    void simultaneousEmailRegistrationDoesNotCreateTwoOwnedProfiles() throws Exception {
        String email = email();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(registration(email)))).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var task : tasks) statuses.add(task.get(20, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
        }
        User account = users.findByEmailIgnoreCase(email).orElseThrow();
        CareSubject own = subjects.findByAccountUserId(account.getId()).orElseThrow();
        assertEquals(1, relationships.findByCareSubjectId(own.getId()).size());
    }

    @Test
    void addingAndInvitingOthersPreservesBothOwnProfilesAndSecondaryRoles() throws Exception {
        Account owner = register(), invited = register();
        long family = send(post("/api/v1/care-subjects"), owner, person("FICTITIOUS"), 201).path("id").asLong();
        long relation = send(post("/api/v1/care-subjects/" + family + "/relationships"), owner,
                Map.of("email", invited.email(), "role", "CAREGIVER"), 201).path("id").asLong();
        send(post("/api/v1/me/relationships/" + relation + "/accept"), invited, null, 200);
        assertEquals(2, send(get("/api/v1/care-subjects"), invited, null, 200).size());
        assertEquals("CAREGIVER", send(get("/api/v1/care-subjects/" + family), invited, null, 200).path("myRole").asString());
        assertEquals(CareRole.PRINCIPAL, relationships.findByUserIdAndCareSubjectId(invited.id(),
                subjects.findByAccountUserId(invited.id()).orElseThrow().getId()).orElseThrow().getRole());
        send(get("/api/v1/care-subjects/" + subjects.findByAccountUserId(owner.id()).orElseThrow().getId()), invited, null, 403);
    }

    @Test
    void existingAccountsAreNotBackfilledOnLoginOrMatchedByName() throws Exception {
        User legacy = users.save(User.builder().email(email()).name("Alias ficticio")
                .passwordHash(new BCryptPasswordEncoder().encode("Ficticia123")).build());
        send(post("/api/v1/auth/login"), null, Map.of("email", legacy.getEmail(), "password", "Ficticia123"), 200);
        assertTrue(subjects.findByAccountUserId(legacy.getId()).isEmpty());
        Account another = register();
        assertNotEquals(legacy.getId(), subjects.findByAccountUserId(another.id()).orElseThrow().getAccountUser().getId());
    }

    @Test
    void aPristineAutomaticProfileIsRetiredWithoutDeletingItWhenReceivingTheRealHistory() throws Exception {
        Account owner = register(), recipient = register();
        long previous = subjects.findByAccountUserId(recipient.id()).orElseThrow().getId();
        long destination = target(owner);
        String token = invitation(owner, recipient, destination);
        send(post("/api/v1/claim-invitations/exchange"), recipient, Map.of("token", token), 200);
        assertTrue(subjects.existsById(previous), "No se elimina el perfil inicial");
        assertNull(subjects.findById(previous).orElseThrow().getAccountUser());
        assertEquals(CareStatus.REVOKED, relationships.findByUserIdAndCareSubjectId(recipient.id(), previous).orElseThrow().getStatus());
        JsonNode network = send(get("/api/v1/care-subjects"), recipient, null, 200);
        assertEquals(1, network.size());
        assertEquals(destination, network.get(0).path("id").asLong());
        assertTrue(network.get(0).path("ownProfile").asBoolean());
        assertFalse(send(get("/api/v1/care-subjects/" + destination), owner, null, 200).path("ownProfile").asBoolean());
        assertEquals(destination, subjects.findByAccountUserId(recipient.id()).orElseThrow().getId());
        assertEquals(1, send(get("/api/v1/care-subjects/" + destination + "/health-entries"), recipient, null, 200).path("totalElements").asInt());
        send(post("/api/v1/claim-invitations/exchange"), recipient, Map.of("token", token), 409);
    }

    @Test
    void receivingAnotherProfileCannotDiscardInformationAlreadyWrittenToTheAutomaticProfile() throws Exception {
        Account owner = register(), recipient = register();
        long own = subjects.findByAccountUserId(recipient.id()).orElseThrow().getId();
        send(post("/api/v1/care-subjects/" + own + "/health-entries"), recipient,
                Map.of("categoryCode", "ALLERGY", "title", "Dato propio ficticio", "confidenceLevel", "CONFIRMED"), 201);
        long destination = target(owner);
        String token = invitation(owner, recipient, destination);
        send(post("/api/v1/claim-invitations/exchange"), recipient, Map.of("token", token), 409);
        assertEquals(own, subjects.findByAccountUserId(recipient.id()).orElseThrow().getId());
        var rejected = invitations.findByTokenHash(TokenHasher.sha256(token)).orElseThrow();
        assertNull(rejected.getExchangedAt());
        assertNull(rejected.getExchangedBy());
        assertTrue(rejected.isValidNow());
        assertNull(subjects.findById(destination).orElseThrow().getAccountUser());
        assertEquals(CareStatus.ACTIVE, relationships.findByUserIdAndCareSubjectId(recipient.id(), own).orElseThrow().getStatus());
        send(get("/api/v1/care-subjects/" + destination), recipient, null, 403);
    }

    @Test
    void provisioningDoesNotOverwriteAnExistingOwnedProfile() throws Exception {
        Account account = register();
        long own = subjects.findByAccountUserId(account.id()).orElseThrow().getId();
        send(patch("/api/v1/care-subjects/" + own), account,
                Map.of("name", "Alias actualizado", "birthDate", "2000-01-01", "notes", "Nota ficticia"), 200);
        new TransactionTemplate(manager).executeWithoutResult(tx -> service.ensureSelfProfile(account.id()));
        JsonNode stored = send(get("/api/v1/care-subjects/" + own), account, null, 200);
        assertEquals("Alias actualizado", stored.path("name").asString());
        assertEquals("2000-01-01", stored.path("birthDate").asString());
        assertEquals("Nota ficticia", stored.path("notes").asString());
        assertEquals(1, relationships.findByCareSubjectId(own).size());
    }

    @Test
    void replacementRechecksCommittedActivityEvenWhenTheOwnProfileWasAlreadyManaged() throws Exception {
        Account owner = register(), recipient = register();
        long own = subjects.findByAccountUserId(recipient.id()).orElseThrow().getId();
        long destination = target(owner);
        String token = invitation(owner, recipient, destination);
        new TransactionTemplate(manager).executeWithoutResult(outer -> {
            assertFalse(subjects.findById(own).orElseThrow().isRegistrationProfileUsed());
            var writer = new TransactionTemplate(manager);
            writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            writer.executeWithoutResult(inner -> {
                try {
                    send(patch("/api/v1/care-subjects/" + own), recipient,
                            Map.of("name", "Perfil actualizado", "notes", "Nota ficticia conservada"), 200);
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            try {
                send(post("/api/v1/claim-invitations/exchange"), recipient, Map.of("token", token), 409);
            } catch (Exception error) { throw new IllegalStateException(error); }
            outer.setRollbackOnly();
        });
        var invitation = invitations.findByTokenHash(TokenHasher.sha256(token)).orElseThrow();
        assertNull(invitation.getExchangedAt());
        assertNull(invitation.getExchangedBy());
        assertNull(subjects.findById(destination).orElseThrow().getAccountUser());
        assertEquals(own, subjects.findByAccountUserId(recipient.id()).orElseThrow().getId());
        assertEquals("Nota ficticia conservada", subjects.findById(own).orElseThrow().getNotes());
    }

    @Test
    void ownershipRecognizesLegacySelfDeclarationsWithoutMatchingNamesOrPrincipalRoles() throws Exception {
        User user = users.save(User.builder().email(email()).name("Alias ficticio")
                .passwordHash(new BCryptPasswordEncoder().encode("Ficticia123")).build());
        JsonNode auth = send(post("/api/v1/auth/login"), null,
                Map.of("email", user.getEmail(), "password", "Ficticia123"), 200);
        Account legacy = new Account(user.getId(), user.getEmail(), auth.path("accessToken").asString());
        assertEquals(0, send(get("/api/v1/care-subjects"), legacy, null, 200).size());
        CareSubject own = subjects.save(CareSubject.builder().name("Alias ficticio").build());
        relationships.save(CareRelationship.builder().user(user).careSubject(own)
                .role(CareRole.PRINCIPAL).status(CareStatus.ACTIVE).build());
        long family = send(post("/api/v1/care-subjects"), legacy, person("FICTITIOUS"), 201).path("id").asLong();
        assertFalse(send(get("/api/v1/care-subjects/" + own.getId()), legacy, null, 200).path("ownProfile").asBoolean());
        send(post("/api/v1/care-subjects/" + own.getId() + "/authorization-declarations"), legacy,
                Map.of("type", "SELF", "textVersion", "1", "confirmed", true), 200);
        JsonNode network = send(get("/api/v1/care-subjects"), legacy, null, 200);
        assertEquals(2, network.size());
        for (JsonNode subject : network) {
            assertEquals(subject.path("id").asLong() == own.getId(), subject.path("ownProfile").asBoolean());
            assertEquals("PRINCIPAL", subject.path("myRole").asString());
        }
        send(post("/api/v1/care-subjects"), legacy, person("SELF"), 409);
        assertEquals(2, send(get("/api/v1/care-subjects"), legacy, null, 200).size());
        assertFalse(send(get("/api/v1/care-subjects/" + family), legacy, null, 200).path("ownProfile").asBoolean());
        assertTrue(subjects.findByAccountUserId(legacy.id()).isEmpty(), "No se hace backfill ni se altera el perfil anterior");

        User holder = users.save(User.builder().email(email()).name("Otro titular ficticio").build());
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                subjects.findById(own.getId()).orElseThrow().setAccountUser(holder));
        assertFalse(send(get("/api/v1/care-subjects/" + own.getId()), legacy, null, 200).path("ownProfile").asBoolean(),
                "Un titular canónico distinto prevalece sobre la declaración histórica");
    }

    @Test
    void retiredAutomaticProfileIsAnonymousAfterClaimAndAccountDeletion() throws Exception {
        Account owner = register();
        String email = email();
        Map<String, Object> registration = new HashMap<>(registration(email));
        registration.put("gender", "FEMALE");
        JsonNode auth = send(post("/api/v1/auth/register"), null, registration, 201);
        Account recipient = new Account(auth.path("user").path("id").asLong(), email, auth.path("accessToken").asString());
        long retired = subjects.findByAccountUserId(recipient.id()).orElseThrow().getId();
        assertEquals(LocalDate.of(1990, 1, 1), subjects.findById(retired).orElseThrow().getBirthDate());
        assertEquals(Gender.FEMALE, subjects.findById(retired).orElseThrow().getGender());
        jdbc.update("update care_relationships set relationship_label = ? where user_id = ? and care_subject_id = ?",
                "Etiqueta personal ficticia", recipient.id(), retired);
        long destination = send(post("/api/v1/care-subjects"), owner, person("LEGAL_REPRESENTATION"), 201).path("id").asLong();
        String token = invitation(owner, recipient, destination);
        send(post("/api/v1/claim-invitations/exchange"), recipient, Map.of("token", token), 200);
        JsonNode received = send(get("/api/v1/care-subjects/" + destination), recipient, null, 200);
        assertEquals("Persona ficticia", received.path("name").asString());
        assertEquals("2000-01-01", received.path("birthDate").asString());
        assertEquals("Perfil automático retirado", jdbc.queryForObject("select name from care_subjects where id = ?", String.class, retired));
        send(delete("/api/v1/users/me"), recipient,
                Map.of("confirmation", "ELIMINAR", "profileIds", List.of(destination)), 204);
        Map<String, Object> row = jdbc.queryForMap("select name, birth_date, gender, notes, account_user_id from care_subjects where id = ?", retired);
        assertEquals("Perfil automático retirado", row.get("name"));
        assertNull(row.get("birth_date"));
        assertNull(row.get("gender"));
        assertNull(row.get("notes"));
        assertNull(row.get("account_user_id"));
        Map<String, Object> link = jdbc.queryForMap("select status, relationship_label, revoked_at from care_relationships where user_id = ? and care_subject_id = ?", recipient.id(), retired);
        assertEquals("REVOKED", link.get("status"));
        assertNull(link.get("relationship_label"));
        assertNotNull(link.get("revoked_at"));
        Map<String, Object> tombstone = jdbc.queryForMap("select name, email, full_name, birth_date, gender, deleted_at from users where id = ?", recipient.id());
        assertEquals("Cuenta eliminada", tombstone.get("name"));
        assertNotEquals(email, tombstone.get("email"));
        assertNull(tombstone.get("full_name"));
        assertNull(tombstone.get("birth_date"));
        assertNull(tombstone.get("gender"));
        assertNotNull(tombstone.get("deleted_at"));
    }

    @Test
    void legacySelfWithHistoryRejectsClaimWithoutConsumingInvitationOrChangingEitherProfile() throws Exception {
        Account owner = register(), recipient = legacyAccount();
        long old = legacySelf(recipient, true);
        long destination = target(owner);
        JsonNode before = send(get("/api/v1/care-subjects/" + old), recipient, null, 200);
        JsonNode history = send(get("/api/v1/care-subjects/" + old + "/health-entries"), recipient, null, 200);
        JsonNode targetBefore = send(get("/api/v1/care-subjects/" + destination), owner, null, 200);
        String token = invitation(owner, recipient, destination);
        send(post("/api/v1/claim-invitations/exchange"), recipient, Map.of("token", token), 409);
        assertEquals(before, send(get("/api/v1/care-subjects/" + old), recipient, null, 200));
        assertEquals(history, send(get("/api/v1/care-subjects/" + old + "/health-entries"), recipient, null, 200));
        assertEquals(targetBefore, send(get("/api/v1/care-subjects/" + destination), owner, null, 200));
        assertNull(subjects.findById(destination).orElseThrow().getAccountUser());
        assertTrue(invitations.findByTokenHash(TokenHasher.sha256(token)).orElseThrow().isValidNow());
        assertNull(invitations.findByTokenHash(TokenHasher.sha256(token)).orElseThrow().getExchangedAt());
        assertEquals(List.of(old), subjects.findOwnProfileIds(recipient.id()));
        assertEquals(CareRole.PRINCIPAL, relationships.findByUserIdAndCareSubjectId(recipient.id(), old).orElseThrow().getRole());
    }

    @Test
    void declaringSelfOnAnotherProfileIsRejectedAndOtherAuthorizationModesRemainAvailable() throws Exception {
        Account actor = register();
        long own = subjects.findByAccountUserId(actor.id()).orElseThrow().getId();
        long other = send(post("/api/v1/care-subjects"), actor, person("FICTITIOUS"), 201).path("id").asLong();
        String historyUrl = "/api/v1/care-subjects/" + other + "/authorization-declarations";
        JsonNode before = send(get(historyUrl), actor, null, 200);
        long previousId = before.get(0).path("id").asLong();
        send(post(historyUrl), actor, Map.of("type", "SELF", "textVersion", "1", "confirmed", true, "previousDeclarationId", previousId), 409);
        assertEquals(before, send(get(historyUrl), actor, null, 200));
        assertEquals(List.of(own), subjects.findOwnProfileIds(actor.id()));
        send(post(historyUrl), actor, Map.of("type", "HOLDER_PERMISSION", "textVersion", "1", "confirmed", true, "previousDeclarationId", previousId), 200);
        assertEquals("HOLDER_PERMISSION", send(get(historyUrl), actor, null, 200).get(0).path("type").asString());
    }

    @Test
    void selfCanBeConfirmedOnTheCorrectCurrentOrLegacyProfile() throws Exception {
        for (Account actor : List.of(register(), legacyAccount())) {
            long own = subjects.findByAccountUserId(actor.id()).map(CareSubject::getId).orElseGet(() -> bareLegacySubject(actor));
            String url = "/api/v1/care-subjects/" + own + "/authorization-declarations";
            Map<String, Object> declaration = Map.of("type", "SELF", "textVersion", "1", "confirmed", true);
            JsonNode confirmed = send(post(url), actor, declaration, 200);
            assertEquals(confirmed, send(post(url), actor, declaration, 200));
            assertEquals(1, send(get(url), actor, null, 200).size());
            assertEquals("ACCESSIBLE", send(get("/api/v1/care-subjects/self-profile-status"), actor, null, 200).path("status").asString());
            assertEquals(List.of(own), subjects.findOwnProfileIds(actor.id()));
        }
    }

    @Test
    void revokedLegacySelfReturnsOnlyAnInaccessibleStatusAndCannotBeDuplicated() throws Exception {
        Account actor = legacyAccount();
        JsonNode none = send(get("/api/v1/care-subjects/self-profile-status"), actor, null, 200);
        assertEquals("NONE", none.path("status").asString());
        assertEquals(1, none.size());
        long own = legacySelf(actor, true);
        var relation = relationships.findByUserIdAndCareSubjectId(actor.id(), own).orElseThrow();
        relation.setStatus(CareStatus.REVOKED);
        relation.setRevokedAt(Instant.now());
        relationships.saveAndFlush(relation);
        assertEquals(0, send(get("/api/v1/care-subjects"), actor, null, 200).size());
        JsonNode state = send(get("/api/v1/care-subjects/self-profile-status"), actor, null, 200);
        assertEquals("INACCESSIBLE", state.path("status").asString());
        assertEquals(1, state.size());
        JsonNode error = send(post("/api/v1/care-subjects"), actor, person("SELF"), 409);
        assertEquals("Ya existe un perfil asociado a ti, pero actualmente no tienes acceso. No es posible crear otro perfil propio.", error.path("message").asString());
        send(get("/api/v1/care-subjects/" + own), actor, null, 403);
        long family = send(post("/api/v1/care-subjects"), actor, person("FICTITIOUS"), 201).path("id").asLong();
        assertNotEquals(own, family);
        assertEquals(CareStatus.REVOKED, relationships.findByUserIdAndCareSubjectId(actor.id(), own).orElseThrow().getStatus());
        mvc.perform(get("/api/v1/care-subjects/self-profile-status").header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void simultaneousSelfDeclarationsOnDifferentProfilesProduceOnlyOneOwnProfile() throws Exception {
        Account actor = legacyAccount();
        long first = bareLegacySubject(actor), second = bareLegacySubject(actor);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> tasks = new ArrayList<>();
            for (long id : List.of(first, second)) tasks.add(executor.submit(() -> {
                start.await();
                return declarationStatus(actor, id);
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var task : tasks) statuses.add(task.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            assertEquals(1, subjects.findOwnProfileIds(actor.id()).size());
        }
    }

    @Test
    void claimAndSelfDeclarationForTheSameAccountCannotBothCreateOwnership() throws Exception {
        Account owner = register(), actor = legacyAccount();
        long candidate = bareLegacySubject(actor), destination = target(owner);
        String token = invitation(owner, actor, destination);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var declaring = executor.submit(() -> { start.await(); return declarationStatus(actor, candidate); });
            var claiming = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/claim-invitations/exchange").header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("token", token))))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int declarationStatus = declaring.get(30, TimeUnit.SECONDS), claimStatus = claiming.get(30, TimeUnit.SECONDS);
            assertTrue((declarationStatus == 200 && claimStatus == 409) || (declarationStatus == 409 && claimStatus == 200));
            assertEquals(1, subjects.findOwnProfileIds(actor.id()).size());
            var invitation = invitations.findByTokenHash(TokenHasher.sha256(token)).orElseThrow();
            assertEquals(claimStatus == 200, invitation.getExchangedAt() != null);
        }
    }

    private Account legacyAccount() throws Exception {
        User user = users.save(User.builder().email(email()).name("Cuenta anterior ficticia")
                .passwordHash(new BCryptPasswordEncoder().encode("Ficticia123")).build());
        JsonNode auth = send(post("/api/v1/auth/login"), null, Map.of("email", user.getEmail(), "password", "Ficticia123"), 200);
        return new Account(user.getId(), user.getEmail(), auth.path("accessToken").asString());
    }

    private long bareLegacySubject(Account actor) {
        CareSubject subject = subjects.save(CareSubject.builder().name("Perfil anterior ficticio").birthDate(LocalDate.of(1980, 1, 1)).build());
        relationships.save(CareRelationship.builder().user(users.getReferenceById(actor.id())).careSubject(subject)
                .role(CareRole.PRINCIPAL).status(CareStatus.ACTIVE).build());
        return subject.getId();
    }

    private long legacySelf(Account actor, boolean history) throws Exception {
        long own = bareLegacySubject(actor);
        send(post("/api/v1/care-subjects/" + own + "/authorization-declarations"), actor,
                Map.of("type", "SELF", "textVersion", "1", "confirmed", true), 200);
        if (history) send(post("/api/v1/care-subjects/" + own + "/health-entries"), actor,
                Map.of("categoryCode", "ALLERGY", "title", "Historial anterior ficticio", "confidenceLevel", "CONFIRMED"), 201);
        return own;
    }

    private int declarationStatus(Account actor, long subjectId) throws Exception {
        return mvc.perform(post("/api/v1/care-subjects/" + subjectId + "/authorization-declarations")
                .header("Authorization", "Bearer " + actor.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("type", "SELF", "textVersion", "1", "confirmed", true))))
                .andReturn().getResponse().getStatus();
    }

    private Account register() throws Exception {
        String email = email();
        JsonNode result = send(post("/api/v1/auth/register"), null, registration(email), 201);
        return new Account(result.path("user").path("id").asLong(), email, result.path("accessToken").asString());
    }
    private String email() { return "self." + UUID.randomUUID() + "@example.invalid"; }
    private Map<String, Object> registration(String email) {
        return Map.of("email", email, "password", "Ficticia123", "name", "Alias ficticio",
                "fullName", "Persona Sintetica", "dni", "12345678", "termsAccepted", true, "termsVersion", terms.current().version());
    }
    private Map<String, Object> person(String type) {
        return Map.of("requestId", UUID.randomUUID().toString(), "name", "Persona ficticia",
                "birthDate", "2000-01-01", "declaration", Map.of("type", type, "textVersion", "1", "confirmed", true));
    }
    private long target(Account owner) throws Exception {
        long id = send(post("/api/v1/care-subjects"), owner, person("LEGAL_REPRESENTATION"), 201).path("id").asLong();
        send(post("/api/v1/care-subjects/" + id + "/health-entries"), owner,
                Map.of("categoryCode", "ALLERGY", "title", "Historial ficticio recibido", "confidenceLevel", "CONFIRMED"), 201);
        return id;
    }
    private String invitation(Account owner, Account recipient, long subject) throws Exception {
        clearInvocations(mail);
        send(post("/api/v1/care-subjects/" + subject + "/claim-invitations"), owner, Map.of("invitedEmail", recipient.email()), 201);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(recipient.email()), eq("Te entregan tu perfil en Yuyay"), html.capture());
        var matcher = Pattern.compile("/entrega/([A-Za-z0-9_-]+)").matcher(html.getValue());
        assertTrue(matcher.find());
        return matcher.group(1);
    }
    private JsonNode send(MockHttpServletRequestBuilder request, Account account, Object body, int expected) throws Exception {
        if (account != null) request.header("Authorization", "Bearer " + account.token());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        byte[] response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray();
        return response.length == 0 ? null : json.readTree(response);
    }
}
