package com.yuyay.admin;

import com.yuyay.attachment.scope.DocumentScopeService;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentScopeSwitchIntegrationTest {
    private static final String SWITCH = "/api/v1/admin/document-scope";
    private static final String SUMMARY = "/api/v1/users/me/document-scope";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DocumentScopeService scopeService;

    /** El contexto se comparte con otras pruebas: el interruptor siempre vuelve a quedar apagado. */
    @AfterEach
    void switchOff() {
        scopeService.setEnabled(false);
    }

    @Test
    void itIsOffByDefaultAndTheSummaryReportsIt() throws Exception {
        String userToken = register("scope.off." + UUID.randomUUID() + "@yuyay.app");

        mvc.perform(get(SWITCH).header("Authorization", "Bearer " + loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get(SUMMARY).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.appropriate").value(0))
                .andExpect(jsonPath("$.unsure").value(0))
                .andExpect(jsonPath("$.strikes").value(0));
    }

    @Test
    void adminTurnsItOnAndOffWithoutRestarting() throws Exception {
        String adminToken = loginAsAdmin();
        String userToken = register("scope.on." + UUID.randomUUID() + "@yuyay.app");

        mvc.perform(put(SWITCH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.modelLoaded").value(true));

        mvc.perform(get(SUMMARY).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.strikes").value(0));

        mvc.perform(put(SWITCH).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.modelLoaded").value(true));

        mvc.perform(get(SUMMARY).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void onlyAdminsCanUseTheSwitch() throws Exception {
        String userToken = register("scope.user." + UUID.randomUUID() + "@yuyay.app");

        mvc.perform(get(SWITCH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mvc.perform(put(SWITCH).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(SWITCH).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(SWITCH).header("Authorization", "Bearer " + loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void theSwitchRequiresAnExplicitValue() throws Exception {
        mvc.perform(put(SWITCH).header("Authorization", "Bearer " + loginAsAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":null}"))
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

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }
}
