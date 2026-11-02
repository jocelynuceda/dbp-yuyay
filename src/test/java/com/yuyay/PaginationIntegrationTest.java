package com.yuyay;

import com.yuyay.audit.entity.AccessAction;
import com.yuyay.audit.entity.AccessLog;
import com.yuyay.audit.repository.AccessLogRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaginationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NotificationRepository notifications;
    @Autowired AccessLogRepository logs;
    @Autowired UserRepository users;
    @Autowired CareSubjectRepository subjects;
    @Autowired JdbcTemplate jdbc;

    private String token;
    private String strangerToken;
    private User owner;
    private User stranger;
    private long subjectId;
    private String entries;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode auth = register();
        token = auth.get("accessToken").asText();
        owner = users.findById(auth.get("user").get("id").asLong()).orElseThrow();
        JsonNode other = register();
        strangerToken = other.get("accessToken").asText();
        stranger = users.findById(other.get("user").get("id").asLong()).orElseThrow();
        subjectId = send(post("/api/v1/care-subjects"), token,
                "{\"name\":\"Rosa\",\"birthDate\":\"1954-01-01\"}", 201).get("id").asLong();
        entries = "/api/v1/care-subjects/" + subjectId + "/health-entries";
    }

    @Test
    void healthPagesHaveStableOrderTotalsCategoryAndExcludeDeletedEntries() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(createEntry(i < 3 ? "ALLERGY" : "CONDITION"));
        long deleted = createEntry("ALLERGY");
        send(delete(entries + "/" + deleted), token, null, 204);
        // Equal timestamps force the id tie-breaker to decide page membership.
        jdbc.update("update health_entries set created_at = ? where care_subject_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), subjectId);

        JsonNode first = send(get(entries).param("size", "2"), token, null, 200);
        assertPage(first, 0, 2, 5, 3, 2);
        assertEquals(ids.get(4).longValue(), first.get("content").get(0).get("id").asLong());
        JsonNode second = send(get(entries).param("size", "2").param("page", "1"), token, null, 200);
        assertPage(second, 1, 2, 5, 3, 2);
        assertEquals(ids.get(2).longValue(), second.get("content").get(0).get("id").asLong());
        assertEquals(ids.get(1).longValue(), second.get("content").get(1).get("id").asLong());
        assertEquals(1, second.get("content").get(0).get("latestVersion").get("versionNumber").asInt());

        JsonNode filtered = send(get(entries).param("category", "ALLERGY").param("size", "2").param("page", "1"), token, null, 200);
        assertPage(filtered, 1, 2, 3, 2, 1);
        assertEquals("ALLERGY", filtered.get("content").get(0).get("categoryCode").asText());
        assertPage(send(get(entries).param("category", ""), token, null, 200), 0, 10, 5, 1, 5);
        assertPage(send(get(entries).param("size", "1000"), token, null, 200), 0, 100, 5, 1, 5);
        assertPage(send(get(entries).param("page", "20"), token, null, 200), 20, 10, 5, 1, 0);
        JsonNode ascending = send(get(entries).param("sort", "id,asc"), token, null, 200);
        assertEquals(ids.get(0).longValue(), ascending.get("content").get(0).get("id").asLong());
        send(get(entries).param("sort", "title,asc"), token, null, 400);
        send(get(entries), strangerToken, null, 403);
    }

    @Test
    void notificationPagesFilterReadStateAndStayWithinCurrentUser() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(notifications.saveAndFlush(Notification.builder().user(owner)
                    .type(NotificationType.ENTRY_CHANGED).title("Aviso " + i)
                    .readAt(i < 2 ? Instant.now() : null).build()).getId());
        }
        notifications.saveAndFlush(Notification.builder().user(stranger)
                .type(NotificationType.ENTRY_CHANGED).title("Privado").build());
        jdbc.update("update notifications set created_at = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), owner.getId());

        JsonNode first = send(get("/api/v1/notifications").param("size", "2"), token, null, 200);
        assertPage(first, 0, 2, 5, 3, 2);
        assertEquals(ids.get(4).longValue(), first.get("content").get(0).get("id").asLong());
        JsonNode second = send(get("/api/v1/notifications").param("size", "2").param("page", "1"), token, null, 200);
        assertPage(second, 1, 2, 5, 3, 2);
        assertEquals(ids.get(2).longValue(), second.get("content").get(0).get("id").asLong());
        JsonNode unread = send(get("/api/v1/notifications").param("unread", "true").param("size", "2").param("page", "1"), token, null, 200);
        assertPage(unread, 1, 2, 3, 2, 1);
        assertFalse(unread.get("content").get(0).get("read").asBoolean());
        JsonNode read = send(get("/api/v1/notifications").param("unread", "false"), token, null, 200);
        assertPage(read, 0, 10, 2, 1, 2);
        assertTrue(read.get("content").get(0).get("read").asBoolean());
        assertPage(send(get("/api/v1/notifications").param("unread", ""), token, null, 200), 0, 10, 5, 1, 5);
        assertPage(send(get("/api/v1/notifications").param("size", "101"), token, null, 200), 0, 100, 5, 1, 5);
        assertPage(send(get("/api/v1/notifications"), strangerToken, null, 200), 0, 10, 1, 1, 1);
        send(patch("/api/v1/notifications/" + ids.get(4) + "/read"), strangerToken, null, 404);
        send(get("/api/v1/notifications").param("unread", "invalid"), token, null, 400);
        send(get("/api/v1/notifications").param("sort", "user.passwordHash,asc"), token, null, 400);
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void accessLogPagesKeepPrincipalPermissionAndSubjectBoundary() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(logs.saveAndFlush(AccessLog.builder().careSubject(subjects.findById(subjectId).orElseThrow())
                    .user(owner).action(AccessAction.VIEW).targetType("HEALTH_ENTRY").targetId((long) i)
                    .occurredAt(Instant.parse("2026-01-01T00:00:00Z")).build()).getId());
        }
        long otherId = send(post("/api/v1/care-subjects"), token,
                "{\"name\":\"Pedro\",\"birthDate\":\"1950-01-01\"}", 201).get("id").asLong();
        logs.saveAndFlush(AccessLog.builder().careSubject(subjects.findById(otherId).orElseThrow())
                .user(owner).action(AccessAction.VIEW).targetType("HEALTH_ENTRY").build());
        String path = "/api/v1/care-subjects/" + subjectId + "/access-logs";
        assertPage(send(get(path), token, null, 200), 0, 20, 5, 1, 5);
        JsonNode first = send(get(path).param("size", "2"), token, null, 200);
        assertPage(first, 0, 2, 5, 3, 2);
        assertEquals(ids.get(4).longValue(), first.get("content").get(0).get("id").asLong());
        JsonNode second = send(get(path).param("size", "2").param("page", "1"), token, null, 200);
        assertPage(second, 1, 2, 5, 3, 2);
        assertEquals(ids.get(2).longValue(), second.get("content").get(0).get("id").asLong());
        assertPage(send(get(path).param("size", "101"), token, null, 200), 0, 100, 5, 1, 5);
        send(get(path), strangerToken, null, 403);
        send(get(path).param("sort", "createdAt,desc"), token, null, 400);
    }

    @Test
    void corsAllowsLocalFrontendsAuthorizationPatchAndDelete() throws Exception {
        for (String origin : List.of("http://localhost:5173", "http://localhost:8081", "http://localhost:3000")) {
            for (String method : List.of("PATCH", "DELETE")) {
                mvc.perform(options(entries).header("Origin", origin)
                                .header("Access-Control-Request-Method", method)
                                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Access-Control-Allow-Origin", origin))
                        .andExpect(header().string("Access-Control-Allow-Headers", "Authorization, Content-Type"));
            }
        }
        mvc.perform(options(entries).header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
    }

    private long createEntry(String category) throws Exception {
        return send(post(entries), token, "{\"categoryCode\":\"" + category
                + "\",\"title\":\"Registro\",\"confidenceLevel\":\"CONFIRMED\"}", 201).get("id").asLong();
    }

    private void assertPage(JsonNode page, int number, int size, long total, int pages, int count) {
        assertEquals(5, page.size(), "Only the stable public page fields are exposed");
        assertEquals(number, page.get("number").asInt());
        assertEquals(size, page.get("size").asInt());
        assertEquals(total, page.get("totalElements").asLong());
        assertEquals(pages, page.get("totalPages").asInt());
        assertEquals(count, page.get("content").size());
    }

    private JsonNode register() throws Exception {
        return send(post("/api/v1/auth/register"), null, "{\"email\":\"pagination."
                + UUID.randomUUID() + "@yuyay.app\",\"password\":\"Secreta123\",\"name\":\"Ana\"}", 201);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String auth, String body, int expected) throws Exception {
        if (auth != null) request.header("Authorization", "Bearer " + auth);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : json.readTree(response);
    }
}
