package com.yuyay.security.handoff;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HandoffCodeGeneratorTest {
    private final HandoffCodeGenerator generator = new HandoffCodeGenerator();
    private final byte[] secret = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Sha256VectorsIncludingLeadingZeros() {
        assertEquals("119246", generator.code(secret, Instant.ofEpochSecond(59)));
        assertEquals("084774", generator.code(secret, Instant.ofEpochSecond(1111111109)));
        assertEquals("062674", generator.code(secret, Instant.ofEpochSecond(1111111111)));
        assertEquals("819424", generator.code(secret, Instant.ofEpochSecond(1234567890)));
        assertEquals("698825", generator.code(secret, Instant.ofEpochSecond(2000000000)));
        assertEquals("737706", generator.code(secret, Instant.ofEpochSecond(20000000000L)));
    }

    @Test
    void acceptsOnlyCurrentThirtySecondStepAndExactlySixAsciiDigits() {
        Instant before = Instant.ofEpochSecond(29, 999_999_999);
        String first = generator.code(secret, Instant.EPOCH);
        assertTrue(generator.matches(secret, first, before));
        assertFalse(generator.matches(secret, first, Instant.ofEpochSecond(30)));
        assertEquals(Instant.ofEpochSecond(30), generator.periodExpiresAt(before));
        assertEquals(Instant.ofEpochSecond(60), generator.periodExpiresAt(Instant.ofEpochSecond(30)));
        assertFalse(generator.matches(secret, "11924", Instant.ofEpochSecond(59)));
        assertFalse(generator.matches(secret, "1192467", Instant.ofEpochSecond(59)));
        assertFalse(generator.matches(secret, "１１９２４６", Instant.ofEpochSecond(59)));
        assertFalse(generator.matches(secret, null, Instant.ofEpochSecond(59)));
    }

    @Test
    void producesIndependent256BitSeedsAndCodesInRange() {
        byte[] first = generator.newSecret();
        byte[] second = generator.newSecret();
        assertEquals(32, first.length);
        assertFalse(Arrays.equals(first, second));
        for (int step = 0; step < 200; step++) {
            String code = generator.code(first, Instant.ofEpochSecond(step * 30L));
            assertTrue(code.matches("[0-9]{6}"));
            assertTrue(Integer.parseInt(code) >= 0 && Integer.parseInt(code) <= 999999);
        }
    }
}
