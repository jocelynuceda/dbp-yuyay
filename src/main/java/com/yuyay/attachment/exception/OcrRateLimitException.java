package com.yuyay.attachment.exception;

import com.yuyay.exception.YuyayException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Thrown when a user exceeds an OCR rate-limit rule. Carries how long the client should wait. */
@Getter
public class OcrRateLimitException extends YuyayException {
    private final long retryAfterSeconds;

    public OcrRateLimitException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
