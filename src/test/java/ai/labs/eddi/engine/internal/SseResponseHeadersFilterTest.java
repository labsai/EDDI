/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerResponse;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B7 / live S-3: every native {@code text/event-stream} response carries
 * {@code Cache-Control: no-cache} and {@code X-Accel-Buffering: no}, so a
 * buffering proxy (nginx by default) passes tokens through as they come instead
 * of delivering a whole turn in one block.
 */
@DisplayName("SSE responses are proxy-safe")
class SseResponseHeadersFilterTest {

    private static HttpServerResponse response(String contentType, String cacheControl) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        if (contentType != null) {
            headers.set("Content-Type", contentType);
        }
        if (cacheControl != null) {
            headers.set("Cache-Control", cacheControl);
        }
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.headers()).thenReturn(headers);
        when(response.putHeader(anyString(), anyString())).thenAnswer(invocation -> {
            headers.set((String) invocation.getArgument(0), (String) invocation.getArgument(1));
            return response;
        });
        return response;
    }

    @Test
    @DisplayName("an event stream gets both headers")
    void eventStreamGetsHeaders() {
        var response = response("text/event-stream", null);

        SseResponseHeadersFilter.addIfEventStream(response);

        assertEquals("no-cache", response.headers().get("Cache-Control"));
        assertEquals("no", response.headers().get("X-Accel-Buffering"));
    }

    @Test
    @DisplayName("a media-type parameter or other casing still counts")
    void parametersAndCase() {
        assertTrue(SseResponseHeadersFilter.isEventStream("text/event-stream;charset=UTF-8"));
        assertTrue(SseResponseHeadersFilter.isEventStream(" Text/Event-Stream "));
        assertFalse(SseResponseHeadersFilter.isEventStream("application/json"));
        assertFalse(SseResponseHeadersFilter.isEventStream("text/event-streams"));
        assertFalse(SseResponseHeadersFilter.isEventStream(null));
    }

    @Test
    @DisplayName("what an endpoint set itself (the /v1 and A2A streams) is left alone — never set twice")
    void endpointValuesWin() {
        var response = response("text/event-stream", "no-store");

        SseResponseHeadersFilter.addIfEventStream(response);

        assertEquals("no-store", response.headers().get("Cache-Control"));
        assertEquals(1, response.headers().getAll("Cache-Control").size());
        assertEquals("no", response.headers().get("X-Accel-Buffering"));
    }

    @Test
    @DisplayName("any other response is untouched")
    void otherResponsesUntouched() {
        var response = response("application/json", null);

        SseResponseHeadersFilter.addIfEventStream(response);

        assertFalse(response.headers().contains("Cache-Control"));
        assertFalse(response.headers().contains("X-Accel-Buffering"));
    }

    private static final Pattern SSE_PRODUCES = Pattern.compile("@Produces\\(([^)]*)\\)");

    /**
     * The contract over all SSE endpoints: the filter is keyed on the media type,
     * so it covers an endpoint exactly when the endpoint declares
     * {@code text/event-stream}. Every resource method that produces server-sent
     * events must declare that type and nothing the filter would not recognise — so
     * a new SSE endpoint is covered without anyone adding it to a list.
     */
    @Test
    @DisplayName("every SSE endpoint in the code base declares the media type the filter keys on")
    void everySseEndpointIsCovered() throws IOException {
        List<String> sseEndpoints = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                var matcher = SSE_PRODUCES.matcher(source);
                while (matcher.find()) {
                    String produces = matcher.group(1);
                    if (!produces.contains("SERVER_SENT_EVENTS") && !produces.contains("text/event-stream")) {
                        continue;
                    }
                    sseEndpoints.add(file.getFileName().toString());
                    boolean onlyEventStream = produces.replace("MediaType.SERVER_SENT_EVENTS", "").replace("SERVER_SENT_EVENTS", "")
                            .replace("\"text/event-stream\"", "").replaceAll("[\\s{},]", "").isEmpty();
                    // A negotiated endpoint (JSON or a stream, like /v1 and A2A) answers its stream
                    // as a
                    // Response it builds itself; it must set the headers there (the filter then
                    // leaves them).
                    if (!onlyEventStream && !source.contains("X-Accel-Buffering")) {
                        uncovered.add(file.getFileName() + ": @Produces(" + produces + ")");
                    }
                }
            }
        }

        assertTrue(sseEndpoints.size() >= 5, "the SSE endpoints were not found: " + sseEndpoints);
        assertTrue(sseEndpoints.contains("IRestAgentEngineStreaming.java"), sseEndpoints.toString());
        assertTrue(uncovered.isEmpty(), "negotiated SSE endpoints that do not set the proxy headers themselves: " + uncovered);
        assertTrue(SseResponseHeadersFilter.isEventStream(MediaType.SERVER_SENT_EVENTS));
    }
}
