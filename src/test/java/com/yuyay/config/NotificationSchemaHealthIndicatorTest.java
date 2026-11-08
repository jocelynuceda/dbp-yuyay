package com.yuyay.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationSchemaHealthIndicatorTest {

    @Test
    void failedConstraintUpdateReportsDownWithTheReason() {
        NotificationTypeConstraintUpdater updater = mock(NotificationTypeConstraintUpdater.class);
        when(updater.status()).thenReturn(NotificationTypeConstraintUpdater.Status.FAILED);
        when(updater.detail()).thenReturn("permiso denegado");

        Health health = new NotificationSchemaHealthIndicator(updater).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("permiso denegado", health.getDetails().get("motivo"));
    }

    @Test
    void otherOutcomesReportUp() {
        for (NotificationTypeConstraintUpdater.Status status : new NotificationTypeConstraintUpdater.Status[]{
                NotificationTypeConstraintUpdater.Status.SKIPPED,
                NotificationTypeConstraintUpdater.Status.UP_TO_DATE,
                NotificationTypeConstraintUpdater.Status.UPDATED}) {
            NotificationTypeConstraintUpdater updater = mock(NotificationTypeConstraintUpdater.class);
            when(updater.status()).thenReturn(status);
            Health health = new NotificationSchemaHealthIndicator(updater).health();
            assertEquals(Status.UP, health.getStatus(), status.name());
        }
    }
}
