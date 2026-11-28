package com.yuyay.auth;

import com.yuyay.care.service.CareSubjectService;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SelfCaregiverRollbackTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CareSubjectRepository subjects;
    @Autowired CareRelationshipRepository relationships;
    @MockitoSpyBean CareSubjectService careSubjects;

    @Test
    void failureAfterCreatingTheProfileAndRelationshipRollsBackTheWholeAccount() throws Exception {
        long beforeSubjects = subjects.count(), beforeRelationships = relationships.count();
        String email = "rollback." + UUID.randomUUID() + "@example.invalid";
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Fallo sintético después de crear la relación");
        }).when(careSubjects).ensureSelfProfile(anyLong());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"Ficticia123","name":"Alias ficticio","fullName":"Persona Sintetica",
                "dni":"12345678","termsAccepted":true,"termsVersion":"1.2"}
                """.formatted(email))).andExpect(status().isInternalServerError());
        assertTrue(users.findByEmailIgnoreCase(email).isEmpty());
        assertEquals(beforeSubjects, subjects.count());
        assertEquals(beforeRelationships, relationships.count());
    }
}
