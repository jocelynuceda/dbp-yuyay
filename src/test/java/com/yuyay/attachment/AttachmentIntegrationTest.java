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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo del OCR: subida, extraccion asincrona y confirmacion por el cuidador.
 * S3 y Textract se simulan: el CI no tiene credenciales de AWS y el test no debe depender de la red.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttachmentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean FileStorage fileStorage;
    @MockitoBean TextExtractor textExtractor;

    private static final byte[] FAKE_IMAGE = "fake-image-bytes".getBytes();

    private String ownerToken;
    private String strangerToken;
    private long subjectId;
    private long otherSubjectId;

    @BeforeEach
    void setUp() throws Exception {
        ownerToken = register("owner." + UUID.randomUUID() + "@yuyay.app");
        subjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"name\":\"Rosa Quispe\",\"birthDate\":\"1948-03-12\"}", 201).get("id").asLong();
        otherSubjectId = postJson("/api/v1/care-subjects", ownerToken,
                "{\"name\":\"Diego Ramos\",\"birthDate\":\"2020-05-01\"}", 201).get("id").asLong();
        strangerToken = register("stranger." + UUID.randomUUID() + "@yuyay.app");
    }

    @Test
    void uploadReturns201AndOcrCompletesInBackground() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("Metformina 850 mg\n1 tableta cada 12 horas");

        JsonNode created = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_IMAGE, 201);
        assertEquals("PENDING", created.get("ocrStatus").asText());
        assertEquals("receta.jpg", created.get("originalFilename").asText());

        JsonNode done = awaitOcr(subjectId, created.get("id").asLong());
        assertEquals("DONE", done.get("ocrStatus").asText());
        assertTrue(done.get("ocrText").asText().contains("Metformina 850 mg"));
        verify(fileStorage).store(startsWith("care-subjects/" + subjectId + "/"), any(byte[].class), eq("image/jpeg"));
    }

    @Test
    void ocrFailureIsStoredAndCanBeRetried() throws Exception {
        when(textExtractor.extractText(anyString()))
                .thenThrow(new RuntimeException("Textract no disponible"))
                .thenReturn("Losartan 50 mg");

        long id = upload(subjectId, ownerToken, "receta.png", "image/png", FAKE_IMAGE, 201).get("id").asLong();
        JsonNode failed = awaitOcr(subjectId, id);
        assertEquals("FAILED", failed.get("ocrStatus").asText());
        assertTrue(failed.get("ocrError").asText().contains("Textract no disponible"));

        mvc.perform(post(url(subjectId) + "/" + id + "/ocr-retry").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ocrStatus").value("PENDING"));

        JsonNode retried = awaitOcr(subjectId, id);
        assertEquals("DONE", retried.get("ocrStatus").asText());
        assertEquals("Losartan 50 mg", retried.get("ocrText").asText());
    }

    @Test
    void retryIsRejectedWhenOcrDidNotFail() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("texto");
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_IMAGE, 201).get("id").asLong();
        awaitOcr(subjectId, id);

        mvc.perform(post(url(subjectId) + "/" + id + "/ocr-retry").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void strangerCannotUploadListOrRead() throws Exception {
        upload(subjectId, strangerToken, "receta.jpg", "image/jpeg", FAKE_IMAGE, 403);
        verifyNoInteractions(fileStorage);

        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_IMAGE, 201).get("id").asLong();

        mvc.perform(get(url(subjectId) + "/" + id).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get(url(subjectId)).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void attachmentOfAnotherSubjectIsNotFound() throws Exception {
        long id = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_IMAGE, 201).get("id").asLong();

        mvc.perform(get(url(otherSubjectId) + "/" + id).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidFilesReturn400() throws Exception {
        upload(subjectId, ownerToken, "nota.txt", "text/plain", FAKE_IMAGE, 400);
        upload(subjectId, ownerToken, "vacia.jpg", "image/jpeg", new byte[0], 400);
        upload(subjectId, ownerToken, "enorme.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1], 400);

        mvc.perform(multipart(url(subjectId)).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(fileStorage);
    }

    @Test
    void confirmedHealthEntryKeepsItsSourceAttachment() throws Exception {
        when(textExtractor.extractText(anyString())).thenReturn("Metformina 850 mg");
        long attachmentId = upload(subjectId, ownerToken, "receta.jpg", "image/jpeg", FAKE_IMAGE, 201).get("id").asLong();

        JsonNode entry = postJson("/api/v1/care-subjects/" + subjectId + "/health-entries", ownerToken,
                entryBody(attachmentId), 201);
        assertEquals(attachmentId, entry.get("latestVersion").get("sourceAttachmentId").asLong());

        postJson("/api/v1/care-subjects/" + otherSubjectId + "/health-entries", ownerToken,
                entryBody(attachmentId), 400);
    }

    private String entryBody(long attachmentId) {
        return "{\"categoryCode\":\"MEDICATION\",\"title\":\"Metformina\",\"dose\":\"850 mg\","
                + "\"confidenceLevel\":\"CONFIRMED\",\"sourceAttachmentId\":" + attachmentId + "}";
    }

    private String url(long subject) {
        return "/api/v1/care-subjects/" + subject + "/attachments";
    }

    private JsonNode upload(long subject, String token, String filename, String contentType,
                            byte[] content, int expected) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, content);
        String body = mvc.perform(multipart(url(subject)).file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode awaitOcr(long subject, long attachmentId) throws Exception {
        for (int i = 0; i < 50; i++) {
            String body = mvc.perform(get(url(subject) + "/" + attachmentId).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = json.readTree(body);
            if (!"PENDING".equals(node.get("ocrStatus").asText())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("El OCR no termino en 5 segundos");
    }

    private String register(String email) throws Exception {
        return postJson("/api/v1/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"Secreta123\",\"name\":\"Test\"}", 201)
                .get("accessToken").asText();
    }

    private JsonNode postJson(String path, String token, String body, int expected) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        String response = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
