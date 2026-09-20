package com.yuyay.notification;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.event.HealthEntryChangedEvent;
import com.yuyay.event.listener.NotificationListener;
import com.yuyay.notification.email.EmailService;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationListenerTest {

    @Mock
    CareRelationshipRepository careRelationshipRepository;

    @Mock
    NotificationRepository notificationRepository;

    @Mock
    EmailService emailService;

    @InjectMocks
    NotificationListener notificationListener;

    @Test
    void healthEntryChangedCreatesNotificationForOtherCaregiver() {

        User destinatario = mock(User.class);
        CareRelationship relationship = mock(CareRelationship.class);

        when(destinatario.getId()).thenReturn(2L);
        when(destinatario.getEmail()).thenReturn("cuidador@yuyay.test");

        when(relationship.getUser()).thenReturn(destinatario);

        when(careRelationshipRepository
                .findByCareSubjectIdAndStatus(
                        1L,
                        CareStatus.ACTIVE
                ))
                .thenReturn(List.of(relationship));

        HealthEntryChangedEvent event =
                new HealthEntryChangedEvent(
                        5L,
                        1L,
                        1L,
                        "UPDATED",
                        "Se actualizó la medicación",
                        "Paciente de prueba"
                );

        notificationListener.onHealthEntryChanged(event);

        ArgumentCaptor<Notification> captor =
                ArgumentCaptor.forClass(Notification.class);

        verify(notificationRepository).save(captor.capture());

        Notification notification = captor.getValue();

        assertEquals(destinatario, notification.getUser());
        assertEquals(
                NotificationType.ENTRY_CHANGED,
                notification.getType()
        );
        assertEquals(1L, notification.getCareSubjectId());
        assertEquals(
                "Cambio en Paciente de prueba",
                notification.getTitle()
        );
        assertEquals(
                "Se actualizó la medicación",
                notification.getBody()
        );

        verify(emailService).sendTemplate(
                eq("cuidador@yuyay.test"),
                eq("Yuyay · cambio en Paciente de prueba"),
                eq("entry-changed"),
                anyMap()
        );
    }
}