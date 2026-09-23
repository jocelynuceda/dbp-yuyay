package com.yuyay.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void adminCanListUsersAndReadStats() throws Exception {
        String adminToken = loginAsAdmin();

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].email").value("admin@yuyay.app"))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.totalElements").exists());

        mvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isNumber())
                .andExpect(jsonPath("$.careSubjects").isNumber());
    }

    @Test
    void adminCanPromoteAnotherUser() throws Exception {
        String adminToken = loginAsAdmin();
        long userId = register("promote." + UUID.randomUUID() + "@yuyay.app").userId();

        mvc.perform(patch("/api/v1/admin/users/" + userId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void regularUserCannotUseAdminEndpoints() throws Exception {
        String userToken = register("user." + UUID.randomUUID() + "@yuyay.app").token();

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mvc.perform(get("/api/v1/admin/stats").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/admin/users/1/role")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void adminCannotRemoveOwnAdminRole() throws Exception {
        String adminToken = loginAsAdmin();
        long adminId = json.readTree(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(patch("/api/v1/admin/users/" + adminId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Un administrador no puede quitarse su propio rol"));
    }

    @Test
    void unknownUserReturns404AndInvalidRoleReturns400() throws Exception {
        String adminToken = loginAsAdmin();

        mvc.perform(get("/api/v1/admin/users/999999").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/v1/admin/users/1/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":null}"))
                .andExpect(status().isBadRequest());
    }

    private String loginAsAdmin() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@yuyay.app\",\"password\":\"Admin12345\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    private record Registered(long userId, String token) {}

    private Registered register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        return new Registered(node.get("user").get("id").asLong(), node.get("accessToken").asText());
    }
}
