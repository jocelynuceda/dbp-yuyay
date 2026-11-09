package com.yuyay.auth;

import com.yuyay.notification.email.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {
    private static final String OLD_PASSWORD = "Secreta123";
    private static final String NEW_PASSWORD = "Nueva4567";
    private static final Pattern CODE = Pattern.compile(">(\\d{6})<");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean EmailSender emailSender;

    @Test
    void validCodeChangesPasswordAndRevokesSessions() throws Exception {
        String email = newEmail();
        String refreshToken = register(email);
        String code = requestCode(email);

        reset(email, code, NEW_PASSWORD).andExpect(status().isNoContent());

        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, OLD_PASSWORD).andExpect(status().isUnauthorized());
        post("/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}").andExpect(status().isUnauthorized());
        reset(email, code, "Otra78901").andExpect(status().isConflict());
    }

    @Test
    void unknownEmailRespondsTheSameAndSendsNothing() throws Exception {
        String email = newEmail();

        post("/forgot-password", emailBody(email)).andExpect(status().isNoContent());

        verify(emailSender, after(500).times(0)).send(eq(email), anyString(), anyString());
    }

    @Test
    void wrongAttemptsAreLimitedEvenWithTheRightCodeAfterwards() throws Exception {
        String email = newEmail();
        register(email);
        String code = requestCode(email);
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int i = 0; i < 5; i++) {
            reset(email, wrong, NEW_PASSWORD).andExpect(status().isConflict());
        }

        reset(email, code, NEW_PASSWORD).andExpect(status().isConflict());
        login(email, OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void repeatedRequestDoesNotSendAnotherCodeRightAway() throws Exception {
        String email = newEmail();
        register(email);
        String code = requestCode(email);

        post("/forgot-password", emailBody(email)).andExpect(status().isNoContent());

        verify(emailSender, after(500).times(1)).send(eq(email), contains("contraseña"), anyString());
        reset(email, code, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void weakPasswordIsRejectedWithoutSpendingTheCode() throws Exception {
        String email = newEmail();
        register(email);
        String code = requestCode(email);

        reset(email, code, "corta").andExpect(status().isBadRequest());

        reset(email, code, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    private String newEmail() {
        return "reset." + UUID.randomUUID() + "@yuyay.app";
    }

    private String register(String email) throws Exception {
        String response = post("/register",
                "{\"email\":\"" + email + "\",\"password\":\"" + OLD_PASSWORD + "\",\"name\":\"Ana\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("refreshToken").asString();
    }

    private String requestCode(String email) throws Exception {
        post("/forgot-password", emailBody(email)).andExpect(status().isNoContent());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(5000)).send(eq(email), contains("contraseña"), html.capture());
        Matcher matcher = CODE.matcher(html.getValue());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private ResultActions reset(String email, String code, String password) throws Exception {
        return post("/reset-password",
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + password + "\"}");
    }

    private ResultActions login(String email, String password) throws Exception {
        return post("/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private String emailBody(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth" + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
