package com.yuyay.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("notificationSchema")
@RequiredArgsConstructor
public class NotificationSchemaHealthIndicator implements HealthIndicator {

    private final NotificationTypeConstraintUpdater updater;

    @Override
    public Health health() {
        NotificationTypeConstraintUpdater.Status status = updater.status();
        if (status == NotificationTypeConstraintUpdater.Status.PENDING) {
            return Health.outOfService().withDetail("estado", status.name()).build();
        }
        if (status == NotificationTypeConstraintUpdater.Status.FAILED) {
            return Health.down().withDetail("motivo", updater.detail()).build();
        }
        return Health.up().withDetail("estado", status.name()).build();
    }
}
