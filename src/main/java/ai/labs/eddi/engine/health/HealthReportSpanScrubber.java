/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Keeps owner report tokens out of traces. The token of an owner report link is
 * the credential, and it is in the request path
 * ({@code /health/reports/<token>}); the server span of such a request would
 * carry it in {@code url.path} (and, with older semantic conventions,
 * {@code http.target} / {@code http.url}) to whatever collector the traces go
 * to. This processor — a CDI bean, which Quarkus registers with the tracer —
 * rewrites those attributes to {@code /health/reports/{token}} as the span
 * starts. The span's name is the route template already.
 * <p>
 * Access logs of a reverse proxy in front of EDDI see the path too; that is the
 * proxy's to scrub (see {@code docs/security.md}).
 */
@ApplicationScoped
public class HealthReportSpanScrubber implements SpanProcessor {

    static final String PREFIX = "/health/reports/";
    static final String TEMPLATE = PREFIX + "{token}";
    static final List<AttributeKey<String>> PATH_KEYS = List.of(AttributeKey.stringKey("url.path"), AttributeKey.stringKey("http.target"),
            AttributeKey.stringKey("http.route"), AttributeKey.stringKey("url.full"), AttributeKey.stringKey("http.url"));

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        for (AttributeKey<String> key : PATH_KEYS) {
            String value = span.getAttribute(key);
            String scrubbed = scrub(value);
            if (scrubbed != null && !scrubbed.equals(value)) {
                span.setAttribute(key, scrubbed);
            }
        }
    }

    /**
     * The value with any token after {@code /health/reports/} replaced by
     * {@code {token}}; unchanged when it holds none.
     */
    static String scrub(String value) {
        if (value == null) {
            return null;
        }
        // Only at the path's root: /agents/{id}/health/reports/{linkId} is the owner
        // side.
        int at = 0;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            at = value.indexOf('/', scheme + 3);
            if (at < 0) {
                return value;
            }
        }
        if (!value.startsWith(PREFIX, at)) {
            return value;
        }
        int start = at + PREFIX.length();
        int end = start;
        while (end < value.length() && value.charAt(end) != '/' && value.charAt(end) != '?' && value.charAt(end) != '#') {
            end++;
        }
        if (end == start || value.startsWith("{token}", start)) {
            return value;
        }
        return value.substring(0, start) + "{token}" + value.substring(end);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Nothing: attributes cannot be changed once a span has ended.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
