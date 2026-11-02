package com.yuyay.notification;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.config.AppProperties;
import com.yuyay.event.CaregiverInvitedEvent;
import com.yuyay.event.DelegationCreatedEvent;
import com.yuyay.event.UserRegisteredEvent;
import com.yuyay.event.listener.EmailListener;
import com.yuyay.notification.email.EmailService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailLinksTest {
    @Mock EmailService email;
    @Mock CareRelationshipRepository relationships;
    @Mock DelegationRepository delegations;
    @Mock UserRepository users;
    private EmailListener listener;

    @BeforeEach
    void setUp() {
        // The API and web hosts differ; trailing slashes must not produce // in paths.
        AppProperties properties = new AppProperties(null, "https://api.example", "https://web.example///", null, null, null);
        listener = new EmailListener(email, relationships, delegations, users, properties);
    }

    @Test
    void caregiverInvitationUsesFrontendRoute() {
        when(relationships.findById(42L)).thenReturn(Optional.of(CareRelationship.builder().id(42L)
                .careSubject(CareSubject.builder().name("Rosa").build())
                .user(User.builder().email("caregiver@example.com").build()).build()));

        listener.onCaregiverInvited(new CaregiverInvitedEvent(42L));

        verify(email).sendTemplate(eq("caregiver@example.com"), eq("Te invitaron a cuidar a Rosa en Yuyay"),
                eq("invitation"), eq(Map.of("careSubjectName", "Rosa", "invitationLink", "https://web.example/invitaciones/42")));
    }

    @Test
    void welcomeUsesFrontendHome() {
        when(users.findById(5L)).thenReturn(Optional.of(User.builder().name("Ana").email("ana@example.com").build()));

        listener.onUserRegistered(new UserRegisteredEvent(5L));

        verify(email).sendTemplate(eq("ana@example.com"), eq("Te damos la bienvenida a Yuyay"), eq("welcome"),
                eq(Map.of("userName", "Ana", "appLink", "https://web.example")));
    }

    @Test
    void delegationEmailPreservesCreatedShareLink() {
        when(delegations.findById(7L)).thenReturn(Optional.of(Delegation.builder()
                .careSubject(CareSubject.builder().name("Rosa").build()).granteeEmail("nurse@example.com").build()));

        listener.onDelegationCreated(new DelegationCreatedEvent(7L, "https://web.example/acceso/opaque-token"));

        verify(email).sendTemplate(eq("nurse@example.com"), eq("Acceso temporal a la informacion de Rosa"), eq("delegation"),
                eq(Map.of("careSubjectName", "Rosa", "shareLink", "https://web.example/acceso/opaque-token")));
    }
}
