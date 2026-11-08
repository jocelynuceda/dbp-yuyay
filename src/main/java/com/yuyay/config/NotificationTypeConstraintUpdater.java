package com.yuyay.config;

import com.yuyay.notification.entity.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// ddl-auto=update no modifica el CHECK que Hibernate generó para el enum en PostgreSQL.
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationTypeConstraintUpdater implements ApplicationRunner {

    public enum Status { PENDING, SKIPPED, UP_TO_DATE, UPDATED, FAILED }

    private static final String FIND_CONSTRAINTS =
            "SELECT c.conname AS conname, pg_get_constraintdef(c.oid) AS def "
                    + "FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey) "
                    + "WHERE c.conrelid = 'notifications'::regclass AND c.contype = 'c' AND a.attname = 'type'";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private volatile Status status = Status.PENDING;
    private volatile String detail;

    public NotificationTypeConstraintUpdater(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Status status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String product = jdbcTemplate.execute(
                    (java.sql.Connection con) -> con.getMetaData().getDatabaseProductName());
            if (product == null || !product.toLowerCase().contains("postgres")) {
                status = Status.SKIPPED;
                log.debug("Restricción de notifications.type no requiere cambios en {}", product);
                return;
            }

            if (jdbcTemplate.queryForObject("SELECT to_regclass('notifications') IS NOT NULL", Boolean.class) != Boolean.TRUE) {
                status = Status.SKIPPED;
                log.debug("La tabla notifications aún no existe; se omite la actualización de la restricción");
                return;
            }

            List<String> values = Arrays.stream(NotificationType.values()).map(Enum::name).toList();
            List<Map<String, Object>> current = jdbcTemplate.queryForList(FIND_CONSTRAINTS);
            if (current.size() == 1 && containsAll(current.get(0), values)) {
                status = Status.UP_TO_DATE;
                log.info("Restricción de notifications.type al día: {}", values);
                return;
            }

            String literals = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
            transactionTemplate.executeWithoutResult(tx -> {
                for (Map<String, Object> row : current) {
                    String name = String.valueOf(row.get("conname"));
                    jdbcTemplate.execute("ALTER TABLE notifications DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
                }
                jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (" + literals + "))");

                List<Map<String, Object>> after = jdbcTemplate.queryForList(FIND_CONSTRAINTS);
                if (after.size() != 1 || !containsAll(after.get(0), values)) {
                    throw new IllegalStateException("La restricción de notifications.type no quedó con todos los valores: " + after);
                }
            });

            status = Status.UPDATED;
            log.info("Restricción de notifications.type actualizada: {} (reemplazadas: {})", literals, current.size());
        } catch (Exception e) {
            status = Status.FAILED;
            detail = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("No se pudo actualizar la restricción de notifications.type", e);
        }
    }

    private static boolean containsAll(Map<String, Object> row, List<String> values) {
        String def = String.valueOf(row.get("def"));
        return values.stream().allMatch(v -> def.contains("'" + v + "'"));
    }
}
