package com.yuyay.config;

import com.yuyay.notification.entity.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// ddl-auto=update no modifica el CHECK que Hibernate generó para el enum en PostgreSQL.
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class NotificationTypeConstraintUpdater implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String product = jdbcTemplate.execute(
                    (java.sql.Connection con) -> con.getMetaData().getDatabaseProductName());
            if (product == null || !product.toLowerCase().contains("postgres")) {
                log.debug("Restricción de notifications.type no requiere cambios en {}", product);
                return;
            }

            if (jdbcTemplate.queryForObject("SELECT to_regclass('notifications') IS NOT NULL", Boolean.class) != Boolean.TRUE) {
                log.debug("La tabla notifications aún no existe; se omite la actualización de la restricción");
                return;
            }

            List<String> dropped = jdbcTemplate.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conrelid = 'notifications'::regclass "
                            + "AND contype = 'c' AND pg_get_constraintdef(oid) ILIKE '%type%'",
                    String.class);

            String values = Arrays.stream(NotificationType.values())
                    .map(t -> "'" + t.name() + "'")
                    .collect(Collectors.joining(", "));

            List<String> statements = new ArrayList<>();
            for (String name : dropped) {
                statements.add("ALTER TABLE notifications DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
            }
            statements.add("ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (" + values + "))");

            jdbcTemplate.batchUpdate(statements.toArray(new String[0]));
            log.info("Restricción de notifications.type actualizada: {} (eliminadas: {})", values, dropped);
        } catch (Exception e) {
            log.warn("No se pudo actualizar la restricción de notifications.type", e);
        }
    }
}
