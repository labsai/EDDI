/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BoundedBodyReader")
class BoundedBodyReaderTest {

    @Test
    @DisplayName("a body under the cap is returned whole and not truncated")
    void underCap() throws IOException {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        BoundedBodyReader.Bounded result = BoundedBodyReader.read(new ByteArrayInputStream(data), 1024, Duration.ofSeconds(5), null);
        assertFalse(result.truncated());
        assertArrayEquals(data, result.bytes());
    }

    @Test
    @DisplayName("a body over the cap is truncated exactly at the cap")
    void overCap() throws IOException {
        byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        BoundedBodyReader.Bounded result = BoundedBodyReader.read(new ByteArrayInputStream(data), 1000, Duration.ofSeconds(5), null);
        assertTrue(result.truncated());
        assertEquals(1000, result.bytes().length);
        for (int i = 0; i < 1000; i++) {
            assertEquals((byte) (i % 251), result.bytes()[i]);
        }
    }

    @Test
    @DisplayName("a body exactly at the cap is not truncated")
    void exactlyAtCap() throws IOException {
        byte[] data = new byte[1000];
        BoundedBodyReader.Bounded result = BoundedBodyReader.read(new ByteArrayInputStream(data), 1000, Duration.ofSeconds(5), null);
        assertFalse(result.truncated());
        assertEquals(1000, result.bytes().length);
    }

    @Test
    @DisplayName("maxBytes <= 0 means unbounded (only the deadline applies)")
    void unbounded() throws IOException {
        byte[] data = new byte[50_000];
        BoundedBodyReader.Bounded result = BoundedBodyReader.read(new ByteArrayInputStream(data), 0, Duration.ofSeconds(5), null);
        assertFalse(result.truncated());
        assertEquals(50_000, result.bytes().length);
    }

    @Test
    @DisplayName("a body that trickles past the deadline is cut short and marked truncated")
    void deadlineTruncates() throws IOException {
        // A stream that returns one byte per read and sleeps 20ms each time. With a
        // 1ms request timeout the budget (x4 = 4ms) expires almost immediately, so the
        // in-loop deadline check fires after a few bytes.
        InputStream slow = new InputStream() {
            private int served = 0;

            @Override
            public int read() {
                return readSingle();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                int value = readSingle();
                if (value == -1) {
                    return -1;
                }
                b[off] = (byte) value;
                return 1;
            }

            private int readSingle() {
                if (served >= 10_000) {
                    return -1;
                }
                served++;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            }
        };
        BoundedBodyReader.Bounded result = BoundedBodyReader.read(slow, 1_000_000, Duration.ofMillis(1), null);
        assertTrue(result.truncated(), "a body slower than the deadline must be reported truncated");
        assertTrue(result.bytes().length < 10_000, "the read must stop well before the whole body arrives");
    }
}
