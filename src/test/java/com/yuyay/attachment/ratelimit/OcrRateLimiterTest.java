package com.yuyay.attachment.ratelimit;

import com.yuyay.attachment.exception.OcrRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure logic of the limiter: an in-memory store and a clock the test moves by hand. No Spring, no database. */
class OcrRateLimiterTest {
    private static final Instant START = Instant.parse("2026-11-14T15:00:00Z");
    private static final Long ANA = 1L;
    private static final Long LUIS = 2L;

    private final MutableClock clock = new MutableClock(START);
    private final FakeStore store = new FakeStore();

    @Test
    void secondRequestInsideTheIntervalIsRejectedWithTheRemainingSeconds() {
        OcrRateLimiter limiter = limiter(5, 0, 0);
        limiter.acquire(ANA);

        clock.advance(Duration.ofMillis(1200));
        OcrRateLimitException tooSoon = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, tooSoon.getStatus());
        assertEquals(4L, tooSoon.getRetryAfterSeconds(), "3.8 s left round up to 4");
        assertEquals("Espera 4 segundos antes de subir otro documento.", tooSoon.getMessage());

        clock.advance(Duration.ofMillis(3300));
        OcrRateLimitException almost = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(1L, almost.getRetryAfterSeconds());
        assertEquals("Espera 1 segundo antes de subir otro documento.", almost.getMessage());

        clock.advance(Duration.ofMillis(500));
        limiter.acquire(ANA);
    }

    @Test
    void aRejectedRequestDoesNotRestartTheInterval() {
        OcrRateLimiter limiter = limiter(5, 0, 0);
        limiter.acquire(ANA);

        clock.advance(Duration.ofSeconds(2));
        assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        clock.advance(Duration.ofSeconds(2));
        assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));

        clock.advance(Duration.ofSeconds(1));
        limiter.acquire(ANA);
    }

    @Test
    void eachUserHasTheirOwnInterval() {
        OcrRateLimiter limiter = limiter(5, 0, 0);
        limiter.acquire(ANA);
        limiter.acquire(LUIS);

        assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertThrows(OcrRateLimitException.class, () -> limiter.acquire(LUIS));
    }

    @Test
    void tooManyDocumentsStillBeingReadAreRejectedWithoutTakingATurn() {
        OcrRateLimiter limiter = limiter(5, 30, 3);
        store.pending.put(ANA, 3L);

        OcrRateLimitException busy = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(OcrRateLimiter.PENDING_RETRY_AFTER_SECONDS, busy.getRetryAfterSeconds());
        assertEquals("Tienes 3 documentos en proceso. Espera a que terminen antes de subir otro.", busy.getMessage());
        assertEquals(START.minus(OcrRateLimiter.PENDING_WINDOW), store.lastPendingSince,
                "only documents uploaded in the last 10 minutes count as pending");
        assertEquals(0, store.requests.size(), "a rejected request is not logged");

        store.pending.put(ANA, 2L);
        limiter.acquire(ANA);
        assertEquals(1, store.requests.size());
    }

    @Test
    void thePendingMessageUsesSingularForOneDocument() {
        OcrRateLimiter limiter = limiter(0, 0, 1);
        store.pending.put(ANA, 1L);

        OcrRateLimitException busy = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals("Tienes 1 documento en proceso. Espera a que termine antes de subir otro.", busy.getMessage());
    }

    @Test
    void theDailyLimitUsesARolling24HourWindow() {
        OcrRateLimiter limiter = limiter(5, 3, 0);
        for (int i = 0; i < 3; i++) {
            limiter.acquire(ANA);
            clock.advance(Duration.ofSeconds(10));
        }

        // 30 s after the first request: it leaves the window in 86 400 - 30 s.
        OcrRateLimitException full = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(86_370L, full.getRetryAfterSeconds());
        assertEquals("Llegaste al límite de 3 documentos en 24 horas. Podrás subir otro en unas 24 horas.",
                full.getMessage());
        assertEquals(3, store.requests.size(), "a rejected request is not logged");

        clock.set(START.plus(Duration.ofHours(24)).minus(Duration.ofMinutes(10)));
        OcrRateLimitException soon = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(600L, soon.getRetryAfterSeconds());
        assertEquals("Llegaste al límite de 3 documentos en 24 horas. Podrás subir otro en unos 10 minutos.",
                soon.getMessage());

        clock.set(START.plus(Duration.ofHours(24)).plusSeconds(1));
        limiter.acquire(ANA);
        assertEquals(3, store.requests.size(), "the request that left the window is pruned when a new one is logged");
    }

    @Test
    void theDailyMessageUsesSingularForOneMinuteAndOneHour() {
        OcrRateLimiter limiter = limiter(0, 1, 0);
        limiter.acquire(ANA);

        clock.set(START.plus(Duration.ofHours(23)));
        OcrRateLimitException hour = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(3600L, hour.getRetryAfterSeconds());
        assertTrue(hour.getMessage().endsWith("Podrás subir otro en 1 hora."), hour.getMessage());

        clock.set(START.plus(Duration.ofHours(24)).minusSeconds(30));
        OcrRateLimitException minute = assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        assertEquals(30L, minute.getRetryAfterSeconds());
        assertTrue(minute.getMessage().endsWith("Podrás subir otro en 1 minuto."), minute.getMessage());
    }

    @Test
    void aRequestRejectedByTheDailyLimitDoesNotTakeTheIntervalTurn() {
        OcrRateLimiter limiter = limiter(5, 1, 0);
        limiter.acquire(ANA);
        clock.advance(Duration.ofSeconds(6));
        assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));

        // The quota frees up one second later: had the rejection taken the turn, the interval would reject this one.
        store.requests.clear();
        clock.advance(Duration.ofSeconds(1));
        limiter.acquire(ANA);
        assertEquals(1, store.requests.size());
    }

    @Test
    void withoutIntervalTheDailyLimitStillCountsBackToBackRequests() {
        OcrRateLimiter limiter = limiter(0, 2, 0);
        limiter.acquire(ANA);
        limiter.acquire(ANA);

        assertThrows(OcrRateLimitException.class, () -> limiter.acquire(ANA));
        limiter.acquire(LUIS);
    }

    @Test
    void disabledRulesNeverTouchTheStore() {
        store.failOnAnyCall = true;

        OcrRateLimiter off = limiter(0, 0, 0);
        for (int i = 0; i < 100; i++) off.acquire(ANA);

        OcrRateLimiter intervalOnly = limiter(5, 0, 0);
        intervalOnly.acquire(ANA);
        assertThrows(OcrRateLimitException.class, () -> intervalOnly.acquire(ANA));

        OcrRateLimiter negative = limiter(-1, -1, -1);
        negative.acquire(ANA);
        negative.acquire(ANA);
    }

    @Test
    void ofManySimultaneousRequestsFromOneUserOnlyOneIsAccepted() throws Exception {
        OcrRateLimiter limiter = limiter(5, 30, 0);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        limiter.acquire(ANA);
                        accepted.incrementAndGet();
                    } catch (OcrRateLimitException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, accepted.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, store.requests.size(), "only the accepted request is logged");
    }

    private OcrRateLimiter limiter(long minIntervalSeconds, int dailyLimit, int maxPending) {
        return new OcrRateLimiter(store, clock, minIntervalSeconds, dailyLimit, maxPending);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** What the limiter needs from persistence, kept in memory. */
    private static final class FakeStore implements OcrUsageStore {
        private record Request(Long userId, Instant at) {}

        final List<Request> requests = new ArrayList<>();
        final Map<Long, Long> pending = new HashMap<>();
        volatile Instant lastPendingSince;
        volatile boolean failOnAnyCall;

        @Override
        public synchronized long countPendingSince(Long userId, Instant since) {
            touch();
            lastPendingSince = since;
            return pending.getOrDefault(userId, 0L);
        }

        @Override
        public synchronized long countRequestsSince(Long userId, Instant since) {
            touch();
            return requests.stream().filter(r -> r.userId().equals(userId) && !r.at().isBefore(since)).count();
        }

        @Override
        public synchronized Optional<Instant> oldestRequestSince(Long userId, Instant since) {
            touch();
            return requests.stream().filter(r -> r.userId().equals(userId) && !r.at().isBefore(since))
                    .map(Request::at).min(Instant::compareTo);
        }

        @Override
        public synchronized void record(Long userId, Instant at) {
            touch();
            requests.add(new Request(userId, at));
        }

        @Override
        public synchronized void deleteOlderThan(Long userId, Instant before) {
            touch();
            requests.removeIf(r -> r.userId().equals(userId) && r.at().isBefore(before));
        }

        private void touch() {
            if (failOnAnyCall) throw new AssertionError("a disabled rule must not use the store");
        }
    }
}
