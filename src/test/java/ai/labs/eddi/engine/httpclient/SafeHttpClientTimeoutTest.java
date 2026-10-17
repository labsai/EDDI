/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.httpclient.outbound.MethodRule;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How {@link SafeHttpClient} turns a JDK request into an outbound request. The
 * per-hop timeout backstop itself — 15 seconds when the caller set none — is
 * applied by {@code OutboundHttp} and pinned in {@code OutboundHttpTest}; here
 * the point is that nothing the caller set is lost or invented on the way.
 * <p>
 * Socket-free, so these always run.
 */
@DisplayName("SafeHttpClient — request conversion")
class SafeHttpClientTimeoutTest {

    private static final URI TARGET = URI.create("https://example.test/resource");
    private static final RedirectPolicy FOLLOW = RedirectPolicy.follow(5, MethodRule.JDK_SAFE);

    private final SafeHttpClient client = new SafeHttpClient(10_000);

    @Test
    @DisplayName("no caller timeout leaves the facade's default in charge")
    void unboundedRequestGetsTheDefault() {
        OutboundRequest converted = client.toOutbound(HttpRequest.newBuilder(TARGET).GET().build(), FOLLOW);

        assertNull(converted.timeoutOverride(), "the facade applies its 15s default to a request with no override");
    }

    @Test
    @DisplayName("a caller's own timeout is never overridden")
    void callerTimeoutWins() {
        Duration callerBound = Duration.ofSeconds(3);
        OutboundRequest converted = client.toOutbound(HttpRequest.newBuilder(TARGET).timeout(callerBound).GET().build(), FOLLOW);

        assertEquals(callerBound, converted.timeoutOverride());
    }

    @Test
    @DisplayName("method, every header value and the body survive the conversion")
    void conversionPreservesTheRequest() {
        HttpRequest original = HttpRequest.newBuilder(TARGET)
                .header("Authorization", "Bearer token-value")
                .header("X-Custom", "one")
                .header("X-Custom", "two")
                .POST(HttpRequest.BodyPublishers.ofString("payload"))
                .build();

        OutboundRequest converted = client.toOutbound(original, FOLLOW);

        assertEquals("POST", converted.method(), "conversion must not silently downgrade the method");
        assertEquals(TARGET, converted.uri());
        assertEquals(List.of("Bearer token-value"), converted.headers().get("authorization"));
        assertEquals(List.of("one", "two"), converted.headers().get("X-Custom"),
                "a repeated header must keep every value, not just the last");
        assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), converted.body().toBytes());
    }

    @Test
    @DisplayName("a GET with no body converts without inventing one")
    void bodylessRequestStaysBodyless() {
        OutboundRequest converted = client.toOutbound(HttpRequest.newBuilder(TARGET).GET().build(), FOLLOW);

        assertEquals("GET", converted.method());
        assertEquals(0, converted.body().toBytes().length);
    }

    @Test
    @DisplayName("the initial URL is trusted and every redirect hop goes through validateRedirectTarget")
    void hopsAreValidatedThroughTheOverridableHook() {
        OutboundRequest converted = client.toOutbound(HttpRequest.newBuilder(TARGET).GET().build(), FOLLOW);

        assertEquals(Profile.DEFAULT, converted.profile());
        assertEquals(SsrfPolicy.HOPS_ONLY, converted.ssrfOverride());
        assertNotNull(converted.hopValidator());
        assertEquals(FOLLOW, converted.redirectOverride());
    }
}
