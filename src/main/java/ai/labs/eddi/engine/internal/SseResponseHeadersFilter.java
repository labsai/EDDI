/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Locale;

/**
 * Makes every {@code text/event-stream} response safe to pass through a proxy:
 * {@code Cache-Control: no-cache} and {@code X-Accel-Buffering: no}.
 * <p>
 * Without them a buffering reverse proxy (nginx by default) holds the tokens of
 * a streamed turn and delivers them in one block when the turn ends, and a
 * cache may keep the stream. The OpenAI-compatible {@code /v1} and the A2A
 * endpoints set both by hand; the native streams (agent turn, resume, group
 * discussion, logs and admin events) set neither.
 * <p>
 * Keyed on the response's media type rather than on a list of endpoints, so a
 * new SSE endpoint is covered without anyone remembering it. The headers are
 * added when the response head is written — the only moment the content type is
 * known — and only when the endpoint did not set them itself, so the endpoints
 * that already do are left exactly as they are.
 */
@ApplicationScoped
public class SseResponseHeadersFilter {

    static final String EVENT_STREAM = "text/event-stream";
    static final String CACHE_CONTROL = "Cache-Control";
    static final String CACHE_CONTROL_VALUE = "no-cache";
    static final String ACCEL_BUFFERING = "X-Accel-Buffering";
    static final String ACCEL_BUFFERING_VALUE = "no";

    /** Next to the HSTS header filter, below the request guards. */
    static final int PRIORITY = 17_000;

    void register(@Observes Filters filters) {
        filters.register(SseResponseHeadersFilter::handle, PRIORITY);
    }

    static void handle(RoutingContext context) {
        HttpServerResponse response = context.response();
        context.addHeadersEndHandler(v -> addIfEventStream(response));
        context.next();
    }

    /**
     * Adds the two headers to {@code response} when it is an event stream and the
     * endpoint did not set them.
     */
    static void addIfEventStream(HttpServerResponse response) {
        if (!isEventStream(response.headers().get("Content-Type"))) {
            return;
        }
        if (response.headers().get(CACHE_CONTROL) == null) {
            response.putHeader(CACHE_CONTROL, CACHE_CONTROL_VALUE);
        }
        if (response.headers().get(ACCEL_BUFFERING) == null) {
            response.putHeader(ACCEL_BUFFERING, ACCEL_BUFFERING_VALUE);
        }
    }

    /**
     * Whether a {@code Content-Type} value names an event stream (parameters
     * allowed).
     */
    static boolean isEventStream(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters).trim();
        }
        return EVENT_STREAM.equals(type);
    }
}
