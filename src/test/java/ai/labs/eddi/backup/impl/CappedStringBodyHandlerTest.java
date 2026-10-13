/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live sync read the source's JSON with an unbounded string handler, so the
 * source decided how much heap one sync took. The capped handler must refuse an
 * over-size declared length before reading, stop an undeclared stream at the
 * cap, and pass anything within it through unchanged.
 */
@DisplayName("CappedStringBodyHandler")
class CappedStringBodyHandlerTest {

    private static HttpResponse.ResponseInfo info(Long contentLength) {
        Map<String, List<String>> headers = contentLength == null ? Map.of() : Map.of("Content-Length", List.of(contentLength.toString()));
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (a, b) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /**
     * A subscription that records whether it was cancelled and how much was
     * requested.
     */
    private static final class RecordingSubscription implements Flow.Subscription {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicLong requested = new AtomicLong();

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a body within the cap comes through unchanged")
    void withinCap() throws Exception {
        var subscriber = new CappedStringBodyHandler(10).apply(info(null));
        var subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("{\"a\":"), bytes("1}")));
        subscriber.onComplete();

        assertEquals("{\"a\":1}", subscriber.getBody().toCompletableFuture().get());
        assertFalse(subscription.cancelled.get());
    }

    @Test
    @DisplayName("an undeclared body is cut off the moment it crosses the cap")
    void streamedOverCap() {
        var subscriber = new CappedStringBodyHandler(10).apply(info(null));
        var subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("0123456789"), bytes("X")));
        subscriber.onComplete();

        var failure = assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
        assertInstanceOf(CappedStringBodyHandler.ResponseTooLargeException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains(RemoteApiResourceSource.MAX_RESPONSE_BYTES_PROPERTY));
        assertTrue(subscription.cancelled.get(), "the transfer is cancelled, not drained");
    }

    @Test
    @DisplayName("a declared length over the cap is refused before anything is requested")
    void declaredOverCap() {
        var subscriber = new CappedStringBodyHandler(10).apply(info(11L));
        var subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        assertTrue(subscription.cancelled.get());
        assertEquals(0, subscription.requested.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    @DisplayName("a too-large failure is recognised through the HTTP client's wrapping")
    void recognisedWhenWrapped() {
        var wrapped = new IOException("send failed", new CappedStringBodyHandler.ResponseTooLargeException(10));
        assertTrue(RemoteApiResourceSource.isTooLarge(wrapped));
        assertFalse(RemoteApiResourceSource.isTooLarge(new IOException("connection reset")));
        assertEquals(RemoteApiResourceSource.DEFAULT_MAX_RESPONSE_BYTES, RemoteApiResourceSource.maxResponseBytes());
    }
}
