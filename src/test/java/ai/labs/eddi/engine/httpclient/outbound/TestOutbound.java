/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Test helpers for code that sends through {@link OutboundHttp}: a facade
 * outside CDI, and canned responses for a mocked one.
 */
public final class TestOutbound {

    private static final URI SOMEWHERE = URI.create("https://example.test/");

    private TestOutbound() {
    }

    /**
     * A real facade on the JDK engine, as {@code new SafeHttpClient(10000)} was.
     */
    public static OutboundHttp standalone() {
        return OutboundHttp.standalone(Duration.ofSeconds(10));
    }

    /**
     * A real facade over {@code engine} — every profile on a 10s connect timeout,
     * SSRF protection flag off, meters kept locally.
     */
    public static OutboundHttp facade(OutboundEngine engine) {
        return new OutboundHttp(OutboundSettings.defaults(Duration.ofSeconds(10)), engine, false, new SimpleMeterRegistry());
    }

    /**
     * A response to return from a mocked {@link OutboundHttp#send}. Generic in its
     * body type so it fits {@code when(http.send(any(), any())).thenReturn(...)},
     * where the stub's type is inferred from the matchers.
     */
    @SuppressWarnings("unchecked")
    public static <T> OutboundResponse<T> response(int status, Object body, boolean truncated) {
        return new OutboundResponse<>(status, null, Map.of(), SOMEWHERE, List.of(), (T) body, truncated);
    }

    /** As {@link #response(int, Object, boolean)}, with response headers. */
    @SuppressWarnings("unchecked")
    public static <T> OutboundResponse<T> response(int status, Object body, Map<String, List<String>> headers) {
        return new OutboundResponse<>(status, null, OutboundRequest.caseInsensitiveCopy(headers), SOMEWHERE, List.of(), (T) body,
                false);
    }
}
