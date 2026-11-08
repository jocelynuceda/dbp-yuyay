package com.yuyay.config;

import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Levanta la app con el perfil demo y revisa los datos a través de la API, como los leen la web y el móvil.
 * Usa su propia base H2 para no borrar la de las demás pruebas (create-drop).
 */
@SpringBootTest(properties = {
        "app.demo.password=Demo12345",
        "spring.datasource.url=jdbc:h2:mem:yuyay-demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
class DemoDataSeederIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoDataSeeder seeder;
    @Autowired UserRepository users;
    @Autowired CareSubjectRepository subjects;
    @Autowired CareRelationshipRepository relationships;
    @Autowired HealthEntryRepository entries;
    @Autowired HealthEntryVersionRepository versions;
    @Autowired ConsultationRepository consultations;
    @Autowired HandoffRepository handoffs;
    @Autowired DelegationRepository delegations;
    @Autowired NotificationRepository notifications;

    @Test
    void anaSeesHerThreePeopleWithPaginatedEntries() throws Exception {
        String ana = login("ana@demo.yuyay");
        JsonNode people = getJson("/api/v1/care-subjects", ana);
        assertEquals(3, people.size());
        long rosa = idByName(people, "Rosa");

        JsonNode page0 = getJson("/api/v1/care-subjects/" + rosa + "/health-entries?page=0&size=10", ana);
        assertEquals(12, page0.get("totalElements").asLong());
        assertEquals(2, page0.get("totalPages").asInt());
        assertEquals(10, page0.get("content").size());

        JsonNode meds = getJson("/api/v1/care-subjects/" + rosa + "/health-entries?category=MEDICATION", ana);
        assertEquals(3, meds.get("totalElements").asLong());

        List<String> categories = StreamSupport.stream(
                getJson("/api/v1/care-subjects/" + rosa + "/health-entries?size=100", ana).get("content").spliterator(), false)
                .map(e -> e.get("categoryCode").asText()).distinct().sorted().toList();
        assertEquals(List.of("ALLERGY", "CONDITION", "EPISODE", "IMMUNIZATION", "MEDICATION"), categories);
    }

    @Test
    void losartanHasTwoVersionsAndTheLatestIsEveryTwelveHours() throws Exception {
        String ana = login("ana@demo.yuyay");
        long rosa = idByName(getJson("/api/v1/care-subjects", ana), "Rosa");
        JsonNode meds = getJson("/api/v1/care-subjects/" + rosa + "/health-entries?category=MEDICATION", ana).get("content");
        JsonNode losartan = StreamSupport.stream(meds.spliterator(), false)
                .filter(e -> e.get("latestVersion").get("title").asText().equals("Losartán"))
                .findFirst().orElseThrow();

        assertEquals(2, losartan.get("latestVersion").get("versionNumber").asInt());
        assertEquals("Cada 12 h", losartan.get("latestVersion").get("frequency").asText());
        JsonNode history = getJson("/api/v1/care-subjects/" + rosa + "/health-entries/" + losartan.get("id").asLong() + "/versions", ana);
        assertEquals(2, history.size());
    }

    @Test
    void rosaHasAFutureConsultationAndAHandoffReadyToShow() throws Exception {
        String ana = login("ana@demo.yuyay");
        long rosa = idByName(getJson("/api/v1/care-subjects", ana), "Rosa");

        JsonNode list = getJson("/api/v1/care-subjects/" + rosa + "/consultations", ana);
        assertEquals(2, list.size());
        long future = StreamSupport.stream(list.spliterator(), false)
                .filter(c -> LocalDate.parse(c.get("date").asText()).isAfter(LocalDate.now())).count();
        assertEquals(1, future);

        JsonNode handoffList = getJson("/api/v1/care-subjects/" + rosa + "/handoffs", ana);
        assertEquals(1, handoffList.size());
        JsonNode handoff = getJson("/api/v1/handoffs/" + handoffList.get(0).get("id").asLong(), ana);
        assertEquals(7, handoff.get("items").size());

        assertEquals(1, getJson("/api/v1/care-subjects/" + rosa + "/delegations", ana).size());
    }

    @Test
    void unreadNotificationsAndPendingInvitationForBruno() throws Exception {
        String ana = login("ana@demo.yuyay");
        assertEquals(3, getJson("/api/v1/notifications/unread-count", ana).get("unreadCount").asLong());

        String bruno = login("bruno@demo.yuyay");
        assertEquals(2, getJson("/api/v1/care-subjects", bruno).size());
        JsonNode pending = getJson("/api/v1/me/relationships/pending", bruno);
        assertEquals(1, pending.size());
    }

    @Test
    void runningTheSeederAgainDoesNotDuplicateAnything() {
        long[] before = counts();
        seeder.seed();
        seeder.seed();
        assertArrayEquals(before, counts());
    }

    private long[] counts() {
        return new long[]{users.count(), subjects.count(), relationships.count(), entries.count(), versions.count(),
                consultations.count(), handoffs.count(), delegations.count(), notifications.count()};
    }

    private String login(String email) throws Exception {
        String body = json.writeValueAsString(Map.of("email", email, "password", "Demo12345"));
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asText();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String response = mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static long idByName(JsonNode people, String name) {
        return StreamSupport.stream(people.spliterator(), false)
                .filter(p -> p.get("name").asText().equals(name))
                .findFirst().orElseThrow().get("id").asLong();
    }
}
