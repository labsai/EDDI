/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Writes the W3C {@code traceparent} / {@code tracestate} headers for an
 * outbound call, so a trace does not stop at EDDI's edge.
 *
 * <p>
 * Used by every outbound stack that is not instrumented by Quarkus on its own:
 * the httpcalls client ({@code HttpClientWrapper}), {@code OutboundHttp}
 * (built-in tools, A2A, ingestion) and the MCP transport (header and
 * {@code params._meta}, SEP-414). They are four different HTTP stacks, so the
 * rules live here once:
 * </p>
 * <ul>
 * <li><b>Only W3C trace context.</b> {@link W3CTraceContextPropagator} and
 * nothing else — in particular never {@code baggage}, which is free-form and
 * can carry user data to a third party that has no business seeing it. The
 * deployment's own propagator list is deliberately not consulted for the same
 * reason.</li>
 * <li><b>Nothing to send, nothing sent.</b> With the OTel SDK disabled (the
 * shipped default, {@code quarkus.otel.sdk.disabled=true}) every span is a
 * no-op with an invalid span context, and an invalid context yields an empty
 * map — never an all-zero {@code traceparent}. No configuration is needed for
 * the default to stay byte-identical to the behaviour before propagation
 * existed.</li>
 * <li><b>Switchable.</b> {@code eddi.tracing.propagate-outbound=false} turns it
 * off while keeping EDDI's own spans, for a deployment that must not reveal its
 * trace ids to the services it calls.</li>
 * <li><b>Never overwrite.</b> A header the agent author configured wins; see
 * {@link #headersMissingFrom}.</li>
 * </ul>
 *
 * <p>
 * Deliberately not built on the Quarkus {@code OpenTelemetry} bean: the W3C
 * propagator is stateless and needs only the {@link Context}, so this works
 * identically whether or not the SDK is installed, and a unit test needs no CDI
 * container to exercise it.
 * </p>
 */
@ApplicationScoped
public class TracePropagation {

    /** The W3C trace context header carrying trace id, parent span id and flags. */
    public static final String TRACEPARENT = "traceparent";

    /** The W3C vendor-specific trace state header. */
    public static final String TRACESTATE = "tracestate";

    /**
     * Every header this class can write, lower-cased. Redirect handling strips
     * these on a cross-origin hop the same way it strips credentials.
     */
    public static final Set<String> HEADERS = Set.of(TRACEPARENT, TRACESTATE);

    private static final TracePropagation DISABLED = new TracePropagation(false);

    private final boolean enabled;

    @Inject
    public TracePropagation(@ConfigProperty(name = "eddi.tracing.propagate-outbound", defaultValue = "true") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * An instance that never writes anything — for collaborators built by hand
     * (unit tests) that have no CDI container to inject one.
     */
    public static TracePropagation disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The trace headers for {@code context}: {@code traceparent} and, when the span
     * carries one, {@code tracestate}. Empty when propagation is off, the context
     * is null, or its span context is invalid (OTel disabled, no span active).
     */
    public Map<String, String> headersFor(Context context) {
        if (!enabled || context == null) {
            return Map.of();
        }
        SpanContext spanContext = Span.fromContext(context).getSpanContext();
        if (!spanContext.isValid()) {
            return Map.of();
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        W3CTraceContextPropagator.getInstance().inject(context, carrier, (map, key, value) -> {
            if (map != null && key != null && value != null) {
                map.put(key.toLowerCase(Locale.ROOT), value);
            }
        });
        // Belt and braces: the W3C propagator writes only these two today. Should a
        // later version add a field, it is not sent until this class says so.
        carrier.keySet().retainAll(HEADERS);
        return carrier;
    }

    /** {@link #headersFor} the calling thread's current context. */
    public Map<String, String> currentHeaders() {
        return headersFor(Context.current());
    }

    /**
     * The current trace headers minus every one the request already carries.
     * <p>
     * Presence is all-or-nothing on purpose: an author who set {@code traceparent}
     * themselves gets neither header from us, because a {@code tracestate} from a
     * different trace next to their {@code traceparent} would be incoherent.
     *
     * @param alreadyPresent
     *            answers whether the request carries a header of that name
     *            (case-insensitively — the caller's header store decides how)
     */
    public Map<String, String> headersMissingFrom(Predicate<String> alreadyPresent) {
        return withoutIfAnyPresent(currentHeaders(), alreadyPresent);
    }

    /**
     * The all-or-nothing rule of {@link #headersMissingFrom} for trace headers that
     * were taken from somewhere other than the current context (the MCP transport
     * reads them from the message, see {@code McpToolProviderManager}): {@code
     * candidates} unchanged when the request carries neither trace header, empty
     * when it carries either.
     */
    public static Map<String, String> withoutIfAnyPresent(Map<String, String> candidates, Predicate<String> alreadyPresent) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        for (String name : HEADERS) {
            if (alreadyPresent.test(name)) {
                return Map.of();
            }
        }
        return candidates;
    }

    /**
     * Whether {@code headerName} is one of the trace headers, case-insensitively.
     */
    public static boolean isTraceHeader(String headerName) {
        return headerName != null && HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
