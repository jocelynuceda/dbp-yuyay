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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OCR rate limit over HTTP. The test profile disables the limits, so this class turns them on.
 * The clock is a mock the test moves by hand; S3 and Textract are mocked as in the other attachment tests.
 */
@SpringBootTest(properties = {
        "app.ocr.min-interval-seconds=5",
        "app.ocr.daily-limit=3",
        "app.ocr.max-pending=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OcrRateLimitIntegrationTest {
    private static final byte[] FAKE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] FAKE_M4A = {0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x4D, 0x34, 0x41, 0x20};
    private static final Instant START = Instant.parse("2026-11-14T15:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean FileStorage fileStorage;
    @MockitoBean TextExtractor textExtractor;
    @MockitoBean Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private String token;
    private long subjectId;

    @BeforeEach
    void setUp() throws Exception {
        now.set(START);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        token = register("limit." + UUID.randomUUID() + "@yuyay.app");
        subjectId = createSubject(token);
    }

    @Test
    void secondDocumentInsideTheIntervalGets429AndIsNotStored() throws Exception {
        uploadImage(subjectId, token).andExpect(status().isCreated());

        advance(Duration.ofSeconds(2));
        uploadImage(subjectId, token)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Espera 3 segundos antes de subir otro documento."));
        verify(fileStorage, times(1)).store(anyString(), any(byte[].class), anyString());

        advance(Duration.ofSeconds(3));
        uploadImage(subjectId, token).andExpect(status().isCreated());
    }

    @Test
    void theLimitIsPerUser() throws Exception {
        String otherToken = register("limit.other." + UUID.randomUUID() + "@yuyay.app");
        long otherSubjectId = createSubject(otherToken);

        uploadImage(subjectId, token).andExpect(status().isCreated());
        uploadImage(otherSubjectId, otherToken).andExpect(status().isCreated());
        uploadImage(subjectId, token).andExpect(status().isTooManyRequests());
    }

    @Test
    void audioIsNeitherLimitedNorCounted() throws Exception {
        upload(subjectId, token, "nota.m4a", "audio/mp4", FAKE_M4A).andExpect(status().isCreated());
        upload(subjectId, token, "otra.m4a", "audio/mp4", FAKE_M4A).andExpect(status().isCreated());

        uploadImage(subjectId, token).andExpect(status().isCreated());
        upload(subjectId, token, "tercera.m4a", "audio/mp4", FAKE_M4A).andExpect(status().isCreated());
        uploadImage(subjectId, token).andExpect(status().isTooManyRequests());
    }

    @Test
    void anInvalidFileDoesNotTakeTheTurn() throws Exception {
        upload(subjectId, token, "nota.txt", "text/plain", FAKE_JPEG).andExpect(status().isBadRequest());

        uploadImage(subjectId, token).andExpect(status().isCreated());
    }

    @Test
    void theDailyLimitRejectsTheNextDocumentUntilTheWindowMoves() throws Exception {
        for (int i = 0; i < 3; i++) {
            uploadImage(subjectId, token).andExpect(status().isCreated());
            advance(Duration.ofSeconds(6));
        }

        // 18 s after the first document: it leaves the 24-hour window in 86 400 - 18 s.
        uploadImage(subjectId, token)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "86382"))
                .andExpect(jsonPath("$.message").value(
                        "Llegaste al límite de 3 documentos en 24 horas. Podrás subir otro en unas 24 horas."));

        now.set(START.plus(Duration.ofHours(24)).plusSeconds(1));
        uploadImage(subjectId, token).andExpect(status().isCreated());
    }

    @Test
    void retryingAFailedOcrAlsoTakesATurn() throws Exception {
        when(textExtractor.extractText(anyString())).thenThrow(new RuntimeException("Textract no disponible"));
        String body = uploadImage(subjectId, token).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        assertEquals("FAILED", awaitOcr(id).get("ocrStatus").asText());

        String retry = "/api/v1/care-subjects/" + subjectId + "/attachments/" + id + "/ocr-retry";
        mvc.perform(post(retry).header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"));

        advance(Duration.ofSeconds(5));
        mvc.perform(post(retry).header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
    }

    private void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    private ResultActions uploadImage(long subject, String userToken) throws Exception {
        return upload(subject, userToken, "receta.jpg", "image/jpeg", FAKE_JPEG);
    }

    private ResultActions upload(long subject, String userToken, String filename, String contentType,
                                 byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, content);
        return mvc.perform(multipart("/api/v1/care-subjects/" + subject + "/attachments").file(file)
                .header("Authorization", "Bearer " + userToken));
    }

    private JsonNode awaitOcr(long attachmentId) throws Exception {
        for (int i = 0; i < 50; i++) {
            String body = mvc.perform(get("/api/v1/care-subjects/" + subjectId + "/attachments/" + attachmentId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(body);
            if (!"PENDING".equals(node.get("ocrStatus").asText())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("OCR did not finish in 5 seconds");
    }

    private long createSubject(String userToken) throws Exception {
        return postJson("/api/v1/care-subjects", userToken,
                "{\"name\":\"Rosa Quispe\",\"birthDate\":\"1948-03-12\"}").get("id").asLong();
    }

    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}")
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
