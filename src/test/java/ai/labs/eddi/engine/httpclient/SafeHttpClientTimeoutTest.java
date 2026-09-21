/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link SafeHttpClient}'s per-hop timeout backstop.
 * <p>
 * The class advertised an "overall wall-clock timeout enforced across all
 * hops", but that budget is only consulted BETWEEN hops. A single hop that
 * completes its handshake and then trickles — or never finishes — its response
 * body therefore hung forever, because control never returned to the check.
 * Redirect hops already carried a fallback; the initial request had whatever
 * the caller set, which for a caller that set nothing was no bound at all.
 * <p>
 * Deliberately separate from {@code SafeHttpClientTest}: that one binds a
 * loopback HTTP server in {@code @BeforeEach}, so it cannot run in environments
 * without loopback sockets. These cases are pure request-shaping and always
 * run.
 */
@DisplayName("SafeHttpClient — per-hop timeout backstop")
class SafeHttpClientTimeoutTest {

    private static final URI TARGET = URI.create("https://example.test/resource");

    @Nested
    @DisplayName("withDefaultTimeout")
    class WithDefaultTimeout {

        @Test
        @DisplayName("a request with no timeout gets the default bound — the hang this fixes")
        void unboundedRequestIsBounded() {
            HttpRequest original = HttpRequest.newBuilder(TARGET).GET().build();
            assertTrue(original.timeout().isEmpty(), "precondition: the caller set no timeout");

            HttpRequest bounded = SafeHttpClient.withDefaultTimeout(original);

            assertTrue(bounded.timeout().isPresent(), "an unbounded request must not reach the wire unbounded");
            assertEquals(Duration.ofSeconds(15), bounded.timeout().orElseThrow());
        }

        @Test
        @DisplayName("a caller's own timeout is never overridden")
        void callerTimeoutWins() {
            Duration callerBound = Duration.ofSeconds(3);
            HttpRequest original = HttpRequest.newBuilder(TARGET).timeout(callerBound).GET().build();

            HttpRequest result = SafeHttpClient.withDefaultTimeout(original);

            assertSame(original, result, "no rebuild is needed when the caller already bounded the request");
            assertEquals(callerBound, result.timeout().orElseThrow());
        }

        @Test
        @DisplayName("the rebuild preserves method, headers and body")
        void rebuildPreservesTheRequest() {
            HttpRequest original = HttpRequest.newBuilder(TARGET)
                    .header("Authorization", "Bearer token-value")
                    .header("X-Custom", "one")
                    .header("X-Custom", "two")
                    .POST(HttpRequest.BodyPublishers.ofString("payload"))
                    .build();

            HttpRequest bounded = SafeHttpClient.withDefaultTimeout(original);

            assertEquals("POST", bounded.method(), "rebuilding must not silently downgrade the method");
            assertEquals(TARGET, bounded.uri());
            assertEquals("Bearer token-value", bounded.headers().firstValue("Authorization").orElse(null));
            assertEquals(List.of("one", "two"), bounded.headers().allValues("X-Custom"),
                    "a repeated header must keep every value, not just the last");
            assertTrue(bounded.bodyPublisher().isPresent(), "the body must survive the rebuild");
            assertEquals(original.bodyPublisher().orElseThrow().contentLength(),
                    bounded.bodyPublisher().orElseThrow().contentLength());
        }

        @Test
        @DisplayName("HttpClient-managed headers are not copied — setting them explicitly is rejected")
        void managedHeadersAreNotCopied() {
            // Content-Length is set by HttpClient from the body publisher; copying
            // it across would throw IllegalArgumentException on build().
            HttpRequest original = HttpRequest.newBuilder(TARGET)
                    .header("X-Kept", "yes")
                    .POST(HttpRequest.BodyPublishers.ofString("payload"))
                    .build();

            HttpRequest bounded = SafeHttpClient.withDefaultTimeout(original);

            assertFalse(bounded.headers().firstValue("Content-Length").isPresent(),
                    "Content-Length is HttpClient's to set");
            assertEquals("yes", bounded.headers().firstValue("X-Kept").orElse(null));
        }

        @Test
        @DisplayName("a GET with no body rebuilds without inventing one")
        void bodylessRequestStaysBodyless() {
            HttpRequest original = HttpRequest.newBuilder(TARGET).GET().build();

            HttpRequest bounded = SafeHttpClient.withDefaultTimeout(original);

            assertEquals("GET", bounded.method());
            assertEquals(0L, bounded.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L),
                    "a GET must not gain a body just because it was rebuilt");
        }
    }
}
