package com.yuyay.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuthSchemaUpdater implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = current_schema AND lower(table_name) = 'users'
                  AND lower(column_name) = 'password_hash'
                """, String.class);
        // ddl-auto=update agrega columnas, pero conserva el NOT NULL de las instalaciones existentes.
        if ("NO".equals(nullable)) {
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL");
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_users_google_subject ON users (google_subject)");
    }
}
