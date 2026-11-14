package com.yuyay.attachment.ratelimit;

import com.yuyay.attachment.exception.OcrRateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user limit for OCR requests: minimum interval between requests, maximum documents still being
 * read, and a daily quota. A value <= 0 disables the corresponding rule. Only who asks and when is used,
 * never the content of the document.
 */
@Service
public class OcrRateLimiter {
    static final Duration WINDOW = Duration.ofHours(24);
    static final Duration PENDING_WINDOW = Duration.ofMinutes(10);
    static final long PENDING_RETRY_AFTER_SECONDS = 10;
    private static final long DEFAULT_DAILY_RETRY_SECONDS = 3600;
    private static final int MAX_TRACKED_USERS = 10_000;

    private final OcrUsageStore store;
    private final Clock clock;
    private final long minIntervalSeconds;
    private final int dailyLimit;
    private final int maxPending;
    private final ConcurrentHashMap<Long, Instant> lastAccepted = new ConcurrentHashMap<>();

    public OcrRateLimiter(OcrUsageStore store, Clock clock,
                          @Value("${app.ocr.min-interval-seconds:5}") long minIntervalSeconds,
                          @Value("${app.ocr.daily-limit:30}") int dailyLimit,
                          @Value("${app.ocr.max-pending:3}") int maxPending) {
        this.store = store;
        this.clock = clock;
        this.minIntervalSeconds = minIntervalSeconds;
        this.dailyLimit = dailyLimit;
        this.maxPending = maxPending;
    }

    /** Accepts the request (consuming a turn) or throws {@link OcrRateLimitException}. */
    public void acquire(Long userId) {
        if (minIntervalSeconds <= 0 && dailyLimit <= 0 && maxPending <= 0) return;
        Instant now = clock.instant();

        checkInterval(lastAccepted.get(userId), now);
        checkPending(userId, now);
        checkDaily(userId, now);
        reserveInterval(userId, now);

        if (dailyLimit > 0) {
            store.deleteOlderThan(userId, now.minus(WINDOW));
            store.record(userId, now);
        }
    }

    private void checkInterval(Instant last, Instant now) {
        if (minIntervalSeconds <= 0 || last == null) return;
        Instant allowedAt = last.plusSeconds(minIntervalSeconds);
        if (now.isBefore(allowedAt)) {
            long seconds = ceilSeconds(Duration.between(now, allowedAt));
            String unit = seconds == 1 ? "1 segundo" : seconds + " segundos";
            throw new OcrRateLimitException("Espera " + unit + " antes de subir otro documento.", seconds);
        }
    }

    private void checkPending(Long userId, Instant now) {
        if (maxPending <= 0) return;
        long pending = store.countPendingSince(userId, now.minus(PENDING_WINDOW));
        if (pending >= maxPending) {
            String message = pending == 1
                    ? "Tienes 1 documento en proceso. Espera a que termine antes de subir otro."
                    : "Tienes " + pending + " documentos en proceso. Espera a que terminen antes de subir otro.";
            throw new OcrRateLimitException(message, PENDING_RETRY_AFTER_SECONDS);
        }
    }

    private void checkDaily(Long userId, Instant now) {
        if (dailyLimit <= 0) return;
        Instant since = now.minus(WINDOW);
        if (store.countRequestsSince(userId, since) < dailyLimit) return;
        long retryAfter = store.oldestRequestSince(userId, since)
                .map(oldest -> ceilSeconds(Duration.between(now, oldest.plus(WINDOW))))
                .orElse(DEFAULT_DAILY_RETRY_SECONDS);
        throw new OcrRateLimitException("Llegaste al límite de " + dailyLimit + " documentos en 24 horas. "
                + "Podrás subir otro en " + humanize(retryAfter) + ".", retryAfter);
    }

    /** Atomically takes the interval slot; of two concurrent requests only one gets it. */
    private void reserveInterval(Long userId, Instant now) {
        if (minIntervalSeconds <= 0) return;
        lastAccepted.compute(userId, (id, last) -> {
            checkInterval(last, now);
            return now;
        });
        if (lastAccepted.size() > MAX_TRACKED_USERS) {
            Instant cutoff = now.minusSeconds(minIntervalSeconds);
            lastAccepted.values().removeIf(last -> last.isBefore(cutoff));
        }
    }

    private static String humanize(long seconds) {
        if (seconds >= 3600) {
            long hours = (seconds + 3599) / 3600;
            return hours == 1 ? "1 hora" : "unas " + hours + " horas";
        }
        long minutes = Math.max(1, (seconds + 59) / 60);
        return minutes == 1 ? "1 minuto" : "unos " + minutes + " minutos";
    }

    private static long ceilSeconds(Duration duration) {
        long millis = duration.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
