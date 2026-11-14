package com.yuyay.attachment.ratelimit;

import java.time.Instant;
import java.util.Optional;

/** Persistence needed by {@link OcrRateLimiter}; the limiter itself knows nothing else about storage. */
public interface OcrUsageStore {
    /** Documents uploaded by the user at or after {@code since} whose OCR is still PENDING. */
    long countPendingSince(Long userId, Instant since);

    /** OCR requests logged for the user at or after {@code since}. */
    long countRequestsSince(Long userId, Instant since);

    Optional<Instant> oldestRequestSince(Long userId, Instant since);

    void record(Long userId, Instant at);

    void deleteOlderThan(Long userId, Instant before);
}
