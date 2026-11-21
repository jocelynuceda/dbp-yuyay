package com.yuyay.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientHeaderTest {

    @Test
    void keepsWellFormedClientNames() {
        assertEquals("web", ClientHeader.sanitize("web"));
        assertEquals("android/1.0.0+0f3a9c12", ClientHeader.sanitize("android/1.0.0+0f3a9c12"));
        assertEquals("ios/unknown+embedded", ClientHeader.sanitize("  ios/unknown+embedded "));
        assertEquals("a".repeat(64), ClientHeader.sanitize("a".repeat(64)));
    }

    /** Sent by the client, so anything that could forge a log line or carry personal text is dropped whole. */
    @Test
    void anythingElseIsUnavailable() {
        for (String value : new String[]{null, "", "   ", "a".repeat(65), "android\nINFO forged line",
                "web; Ana Pérez", "Mozilla/5.0 (Linux; Android 14)", "we\u0000b", "ñandú/1.0"}) {
            assertEquals("unavailable", ClientHeader.sanitize(value), String.valueOf(value));
        }
    }
}
