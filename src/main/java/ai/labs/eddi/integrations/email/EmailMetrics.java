/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * The email channel's meters (email plan §20). Prometheus allows one tag set
 * per meter name, so every meter here always carries the same tags:
 * {@code integration} (the integration's resource id), {@code transport} and
 * {@code mode}, plus its own outcome tag. {@code eddi.email.auth{result}} stays
 * deployment-wide in {@code SenderAuthenticator}, which has no integration to
 * tag.
 */
public final class EmailMetrics {

    public static final String INBOUND = "eddi.email.inbound";
    public static final String POLL_DURATION = "eddi.email.poll.duration";
    public static final String CURSOR_RESETS = "eddi.email.poll.cursor_resets";
    public static final String STRANDED = "eddi.email.ledger.stranded_recovered";
    public static final String MARKED = "eddi.email.poll.marked";

    /** Inbound outcomes, a bounded set. */
    public static final String PROCESSED = "processed";
    public static final String DUPLICATE = "duplicate";
    public static final String UNAUTHENTICATED = "unauthenticated";
    public static final String BLOCKED = "blocked";
    public static final String LOOP = "loop";
    public static final String THROTTLED = "throttled";
    public static final String BUDGET = "budget";
    public static final String TOO_LARGE = "too_large";
    public static final String PARSE_ERROR = "parse_error";
    public static final String DEFERRED = "deferred";
    public static final String UNROUTABLE = "unroutable";
    public static final String FAILED = "failed";

    private final MeterRegistry registry;

    public EmailMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void inbound(String integrationId, String transport, String outcome) {
        if (registry != null) {
            Counter.builder(INBOUND).description("Inbound mail by outcome").tag("integration", integrationId).tag("transport", transport)
                    .tag("mode", "trigger").tag("outcome", outcome).register(registry).increment();
        }
    }

    public void pollDuration(String integrationId, String transport, String outcome, Duration duration) {
        if (registry != null) {
            Timer.builder(POLL_DURATION).description("Duration of one mailbox poll").tag("integration", integrationId).tag("transport", transport)
                    .tag("mode", "trigger").tag("outcome", outcome).register(registry).record(duration);
        }
    }

    public void cursorReset(String integrationId, String transport) {
        if (registry != null) {
            Counter.builder(CURSOR_RESETS).description("Mailbox cursor resets (UIDVALIDITY changed)").tag("integration", integrationId)
                    .tag("transport", transport).tag("mode", "trigger").register(registry).increment();
        }
    }

    /**
     * What marking a processed mail did: flagged, moved, flagged_not_moved, nothing
     * (the server takes no keywords and no move was asked for).
     */
    public void marked(String integrationId, String transport, String result) {
        if (registry != null) {
            Counter.builder(MARKED).description("Processed mail marked in the mailbox, by what the server allowed").tag("integration", integrationId)
                    .tag("transport", transport).tag("mode", "trigger").tag("result", result).register(registry).increment();
        }
    }

    public void stranded(String outcome) {
        if (registry != null) {
            Counter.builder(STRANDED).description("Submitted email turns resolved by the stranded-turn sweep").tag("outcome", outcome)
                    .register(registry).increment();
        }
    }
}
