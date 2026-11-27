/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogExceptionText}: what ends up in
 * {@code LogEntry.exception()}.
 */
class LogExceptionTextTest {

    private static final String KEY = "sk-ant-abcdefghijklmnopqrstuvwxyz01";

    /**
     * Written as a number: the formatter decodes a unicode escape back to the raw
     * character.
     */
    private static final char LINE_SEPARATOR = 0x2028;

    private static Throwable withFrames(Throwable t, int count) {
        StackTraceElement[] frames = new StackTraceElement[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new StackTraceElement("com.example.C" + i, "m" + i, "C" + i + ".java", i + 1);
        }
        t.setStackTrace(frames);
        return t;
    }

    @Test
    @DisplayName("no throwable, no text")
    void nullThrowable() {
        assertNull(LogExceptionText.render(null, 10));
    }

    @Test
    @DisplayName("type, message, cause chain and the first frames of each throwable")
    void causeChainAndFrameCap() {
        Throwable cause = withFrames(new IOException("disk gone"), 4);
        Throwable top = withFrames(new IllegalStateException("sync failed", cause), 25);

        String text = LogExceptionText.render(top, 3);

        assertTrue(text.startsWith("java.lang.IllegalStateException: sync failed\n\tat com.example.C0.m0(C0.java:1)"), text);
        assertTrue(text.contains("\n\t... 22 more"), "25 frames, 3 shown: " + text);
        assertTrue(text.contains("\nCaused by: java.io.IOException: disk gone\n\tat com.example.C0.m0(C0.java:1)"), text);
        assertTrue(text.contains("\n\t... 1 more"), "4 frames, 3 shown: " + text);
        assertFalse(text.contains("C3.m3"), "frames past the cap are not kept");
        assertFalse(text.contains("C24"), "frames past the cap are not kept");
    }

    @Test
    @DisplayName("a zero frame cap keeps the type and message only")
    void zeroFrames() {
        String text = LogExceptionText.render(withFrames(new RuntimeException("x"), 5), 0);

        assertTrue(text.startsWith("java.lang.RuntimeException: x"));
        assertFalse(text.contains("com.example"));
    }

    @Test
    @DisplayName("a secret in the exception message, or in a cause's, is redacted")
    void secretRedacted() {
        Throwable top = new RuntimeException("calling provider with " + KEY, new IOException("retry with " + KEY));

        String text = LogExceptionText.render(top, 5);

        assertFalse(text.contains(KEY), "the raw credential must not be stored: " + text);
        assertTrue(text.contains("sk-ant-<REDACTED>"), text);
        assertEquals(2, text.split("sk-ant-<REDACTED>", -1).length - 1, "both messages redacted");
    }

    @Test
    @DisplayName("CR/LF and line separators in a message or a frame cannot forge a record boundary")
    void boundaryForgeryEscaped() {
        Throwable top = new RuntimeException("boom\r\n2026-01-01 INFO [x] forged line" + LINE_SEPARATOR + "more");
        top.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.example.C", "m\nINFO forged frame", "C.java", 1)});

        String text = LogExceptionText.render(top, 5);

        assertFalse(text.contains("\r"), text);
        assertFalse(text.contains(String.valueOf(LINE_SEPARATOR)), text);
        for (String line : text.split("\n")) {
            assertFalse(line.startsWith("2026-") || line.startsWith("INFO"), "a forged record started a line: " + line);
        }
        assertTrue(text.contains("boom\\r\\n2026-01-01"), "escaped, not dropped: " + text);
    }

    @Test
    @DisplayName("the text is capped, with a marker, and never splits a surrogate pair")
    void totalCap() {
        Throwable top = new RuntimeException("😀".repeat(LogExceptionText.MAX_CHARS));

        String text = LogExceptionText.render(top, 10);

        assertTrue(text.length() <= LogExceptionText.MAX_CHARS, "length " + text.length());
        assertTrue(text.endsWith(LogExceptionText.TRUNCATION_MARKER));
        String body = text.substring(0, text.length() - LogExceptionText.TRUNCATION_MARKER.length());
        assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)), "cut inside a surrogate pair");
    }

    @Test
    @DisplayName("a cyclic cause chain terminates")
    void cyclicCause() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        String text = LogExceptionText.render(a, 0);

        assertTrue(text.startsWith("java.lang.RuntimeException: a"), text);
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: b"), text);
        assertEquals(1, text.split("Caused by:", -1).length - 1, "the cycle back to a is not followed: " + text);
    }

    @Test
    @DisplayName("a RedactedThrowable stand-in is reported under the original type name")
    void standInKeepsTypeName() {
        Throwable stand = RedactedThrowable.of(new IOException("x"), s -> s);

        assertTrue(LogExceptionText.render(stand, 0).startsWith("java.io.IOException: x"));
    }

    @Test
    @DisplayName("a throwable whose accessors throw yields a marker, never raw text")
    void renderFailureIsFailClosed() {
        Throwable hostile = new RuntimeException("secret " + KEY) {
            @Override
            public synchronized Throwable getCause() {
                throw new IllegalStateException("boom");
            }
        };

        assertEquals(LogExceptionText.RENDER_FAILED, LogExceptionText.render(hostile, 3));
    }
}
