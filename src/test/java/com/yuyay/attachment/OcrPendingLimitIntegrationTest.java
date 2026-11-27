package com.yuyay.attachment;

import com.yuyay.attachment.ocr.TextExtractor;
import com.yuyay.attachment.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pending rule over HTTP: only one document may be in OCR at a time. A document retried long after it was
 * uploaded must count as pending, because the rule looks at when the OCR was requested, not when the file
 * was uploaded.
 */
@SpringBootTest(properties = {
        "app.ocr.min-interval-seconds=0",
        "app.ocr.daily-limit=0",
        "app.ocr.max-pending=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OcrPendingLimitIntegrationTest {
    private static final byte[] FAKE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean FileStorage fileStorage;
    @MockitoBean TextExtractor textExtractor;
    @MockitoBean Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>();
    private String token;
    private long subjectId;

    @BeforeEach
    void setUp() throws Exception {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(invocation -> now.get());
        token = register("pending." + UUID.randomUUID() + "@yuyay.app");
        subjectId = postJson("/api/v1/care-subjects", token,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"declaration\":{\"type\":\"FICTITIOUS\",\"textVersion\":\"1\",\"confirmed\":true},\"name\":\"Rosa Quispe\",\"birthDate\":\"1948-03-12\"}").get("id").asLong();
    }

    @Test
    void aDocumentRetriedLongAfterItsUploadCountsAsPending() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(textExtractor.extractText(anyString()))
                .thenThrow(new RuntimeException("Textract no disponible"))
                .thenAnswer(invocation -> {
                    reading.countDown();
                    if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("The test OCR was not released");
                    return "Losartan 50 mg";
                });

        long id = idOf(uploadImage().andExpect(status().isCreated()));
        assertEquals("FAILED", awaitOcr(id).get("ocrStatus").asText());

        // Half an hour later the upload date is outside the 10-minute window; the retry date is not.
        now.set(now.get().plus(Duration.ofMinutes(30)));
        try {
            mvc.perform(post(url() + "/" + id + "/ocr-retry").header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted());
            assertTrue(reading.await(5, TimeUnit.SECONDS), "the retried OCR should be running");

            uploadImage()
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "10"))
                    .andExpect(jsonPath("$.message").value(
                            "Tienes 1 documento en proceso. Espera a que termine antes de subir otro."));
        } finally {
            finish.countDown();
        }

        assertEquals("DONE", awaitOcr(id).get("ocrStatus").asText());
        uploadImage().andExpect(status().isCreated());
    }

    private String url() {
        return "/api/v1/care-subjects/" + subjectId + "/attachments";
    }

    private ResultActions uploadImage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receta.jpg", "image/jpeg", FAKE_JPEG);
        return mvc.perform(multipart(url()).file(file).header("Authorization", "Bearer " + token));
    }

    private long idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode awaitOcr(long attachmentId) throws Exception {
        for (int i = 0; i < 50; i++) {
            String body = mvc.perform(get(url() + "/" + attachmentId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(body);
            if (!"PENDING".equals(node.get("ocrStatus").asText())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("OCR did not finish in 5 seconds");
    }

    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"termsAccepted\":true,\"termsVersion\":\"1.2\",\"fullName\":\"Persona Sintetica\",\"dni\":\"12345678\",\"password\":\"Secreta123\",\"name\":\"Test\"}")
                .get("accessToken").asText();
    }

    private JsonNode postJson(String path, String userToken, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (userToken != null) request = request.header("Authorization", "Bearer " + userToken);
        String response = mvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
