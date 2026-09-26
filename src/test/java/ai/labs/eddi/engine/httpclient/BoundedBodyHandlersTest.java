/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.httpclient.BodyHandlerProbe.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoundedBodyHandlers} without a server: the limit logic is driven
 * through {@link BodyHandlerProbe}, exactly as the JDK client would drive it.
 */
@DisplayName("BoundedBodyHandlers")
class BoundedBodyHandlersTest {

    @Test
    @DisplayName("a body within the limit is delivered whole")
    void withinLimit() {
        Outcome outcome = BodyHandlerProbe.streamed(BoundedBodyHandlers.ofByteArray(200_000), 200_000);

        assertFalse(outcome.refused());
        assertFalse(outcome.cancelled());
        assertEquals(200_000, ((byte[]) outcome.body()).length);
    }

    @Test
    @DisplayName("a streamed body is cut off, and the connection cancelled, the moment it passes the limit")
    void streamedOverLimit() {
        // Ten times the limit on offer: the handler must stop reading, not buffer it.
        Outcome outcome = BodyHandlerProbe.streamed(BoundedBodyHandlers.ofByteArray(100_000), 1_000_000);

        assertTrue(outcome.refused(), "the send must fail with ResponseTooLargeException");
        assertTrue(outcome.cancelled(), "the subscription must be cancelled so the client closes the connection");
    }

    @Test
    @DisplayName("a declared Content-Length over the limit is refused before a byte is requested")
    void declaredOverLimit() {
        Outcome outcome = BodyHandlerProbe.declared(BoundedBodyHandlers.ofByteArray(1024), 5L * 1024 * 1024 * 1024);

        assertTrue(outcome.refused());
        assertTrue(outcome.cancelled());
        assertFalse(outcome.requested(), "nothing of the body may be read when its declared size is already too large");
    }

    @Test
    @DisplayName("exactly the limit is allowed; one byte more is not")
    void boundary() {
        assertFalse(BodyHandlerProbe.streamed(BoundedBodyHandlers.ofByteArray(70_000), 70_000).refused());
        assertTrue(BodyHandlerProbe.streamed(BoundedBodyHandlers.ofByteArray(70_000), 70_001).refused());
        assertFalse(BodyHandlerProbe.declared(BoundedBodyHandlers.ofByteArray(70_000), 70_000).refused());
    }

    @Test
    @DisplayName("ofString applies the same limit to the undecoded bytes")
    void ofStringIsBounded() {
        assertTrue(BodyHandlerProbe.streamed(BoundedBodyHandlers.ofString(10), 11).refused());
        assertTrue(BodyHandlerProbe.declared(BoundedBodyHandlers.ofString(10), 11).refused());
        assertEquals("", BodyHandlerProbe.streamed(BoundedBodyHandlers.ofString(10), 0).body());
    }

    @Test
    @DisplayName("a negative or array-busting limit is a programming error")
    void invalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> BoundedBodyHandlers.ofByteArray(-1));
        assertThrows(IllegalArgumentException.class, () -> BoundedBodyHandlers.ofString(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("the charset comes from Content-Type, defaulting to UTF-8 when absent or unknown")
    void charset() {
        assertEquals(StandardCharsets.ISO_8859_1, BoundedBodyHandlers.charsetFrom(headers("text/html; charset=ISO-8859-1")));
        assertEquals(StandardCharsets.UTF_16, BoundedBodyHandlers.charsetFrom(headers("text/plain;CHARSET=\"utf-16\"")));
        assertEquals(StandardCharsets.UTF_8, BoundedBodyHandlers.charsetFrom(headers("application/json")));
        assertEquals(StandardCharsets.UTF_8, BoundedBodyHandlers.charsetFrom(headers("text/html; charset=no-such-charset")));
        assertEquals(StandardCharsets.UTF_8, BoundedBodyHandlers.charsetFrom(HttpHeaders.of(Map.of(), (a, b) -> true)));
    }

    @Test
    @DisplayName("bytes are copied out of the client's buffers, in order")
    void bytesCopiedInOrder() {
        var subscriber = new BoundedBodyHandlers.LimitingSubscriber(10, OptionalLong.empty());
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
            }
        });
        var first = ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII));
        subscriber.onNext(List.of(first, ByteBuffer.wrap("de".getBytes(StandardCharsets.US_ASCII))));
        // The client may recycle a buffer once onNext returns.
        first.clear();
        first.put("XYZ".getBytes(StandardCharsets.US_ASCII));
        subscriber.onComplete();

        assertArrayEquals("abcde".getBytes(StandardCharsets.US_ASCII), subscriber.getBody().toCompletableFuture().join());
    }

    private static HttpHeaders headers(String contentType) {
        return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (a, b) -> true);
    }
}
