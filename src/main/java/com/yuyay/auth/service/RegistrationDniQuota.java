package com.yuyay.auth.service;

import com.yuyay.config.AppProperties;
import com.yuyay.exception.AuthFlowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

@Service
public class RegistrationDniQuota {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecretKeySpec key;
    private final int perOrigin;
    private final int dailyLimit;

    public RegistrationDniQuota(JdbcTemplate jdbc, Clock clock, AppProperties properties,
            @Value("${app.registration.dni-per-origin}") int perOrigin,
            @Value("${app.registration.dni-daily-limit}") int dailyLimit) {
        if (perOrigin < 1 || dailyLimit < 1) {
            throw new IllegalArgumentException("Los límites de consultas de DNI deben ser positivos");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.key = new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.perOrigin = perOrigin;
        this.dailyLimit = dailyLimit;
    }

    @Transactional
    public void reserve(String origin) {
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        // El mismo orden en todas las instancias; si falla el segundo cupo se revierte el primero.
        increment(day, "global", dailyLimit);
        increment(day, originKey(day, origin), perOrigin);
    }

    private void increment(LocalDate day, String bucket, int limit) {
        jdbc.update("""
                INSERT INTO registration_dni_quotas (bucket, quota_day, used_count)
                VALUES (?, ?, 0) ON CONFLICT DO NOTHING
                """, bucket, day);
        int reserved = jdbc.update("""
                UPDATE registration_dni_quotas SET used_count = used_count + 1
                WHERE bucket = ? AND quota_day = ? AND used_count < ?
                """, bucket, day, limit);
        if (reserved == 0) {
            throw new AuthFlowException(HttpStatus.TOO_MANY_REQUESTS, "DNI_LOOKUP_LIMIT",
                    "Se alcanzó el límite de consultas de DNI. Inténtalo más tarde.");
        }
    }

    private String originKey(LocalDate day, String origin) {
        try {
            Mac mac = Mac.getInstance(key.getAlgorithm());
            mac.init(key);
            byte[] data = ("registration-dni:" + day + ":" + origin).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular la clave del contador", e);
        }
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void deleteExpired() {
        jdbc.update("DELETE FROM registration_dni_quotas WHERE quota_day < ?",
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
