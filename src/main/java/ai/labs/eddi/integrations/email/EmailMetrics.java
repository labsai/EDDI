/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * The email channel's meters (email plan §20). Prometheus allows one tag set
 * per meter name, so every meter here always carries the same tags:
 * {@code integration} (the integration's resource id), {@code transport} and
 * {@code mode} ({@code trigger} or {@code conversation}), plus its own outcome
 * tag. {@code eddi.email.auth{result}} stays deployment-wide in
 * {@code SenderAuthenticator}, which has no integration to tag; the outbound
 * counter {@code eddi.email.outbound{kind,outcome}} is shared with the HITL and
 * owner notifiers and keeps their two tags.
 */
public final class EmailMetrics {

    public static final String INBOUND = "eddi.email.inbound";
    public static final String POLL_DURATION = "eddi.email.poll.duration";
    public static final String CURSOR_RESETS = "eddi.email.poll.cursor_resets";
    public static final String STRANDED = "eddi.email.ledger.stranded_recovered";
    public static final String MARKED = "eddi.email.poll.marked";
    public static final String OUTBOUND = "eddi.email.outbound";
    public static final String OUTBOUND_LATENCY = "eddi.email.outbound.latency";
    public static final String SUPPRESSIONS = "eddi.email.suppressions";

    /** Inbound outcomes, a bounded set. */
    public static final String PROCESSED = "processed";
    public static final String DUPLICATE = "duplicate";
    public static final String UNAUTHENTICATED = "unauthenticated";
    public static final String BLOCKED = "blocked";
    public static final String LOOP = "loop";
    public static final String BOUNCE = "bounce";
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

    static String mode(EmailChannelSettings settings) {
        return settings != null && settings.mode() != null ? settings.mode().id() : "unknown";
    }

    static String transport(EmailChannelSettings settings) {
        return settings != null && settings.transport() != null ? settings.transport() : "unknown";
    }

    public void inbound(String integrationId, EmailChannelSettings settings, String outcome) {
        if (registry != null) {
            Counter.builder(INBOUND).description("Inbound mail by outcome").tag("integration", integrationId).tag("transport", transport(settings))
                    .tag("mode", mode(settings)).tag("outcome", outcome).register(registry).increment();
        }
    }

    public void pollDuration(String integrationId, EmailChannelSettings settings, String outcome, Duration duration) {
        if (registry != null) {
            Timer.builder(POLL_DURATION).description("Duration of one mailbox poll").tag("integration", integrationId)
                    .tag("transport", transport(settings)).tag("mode", mode(settings)).tag("outcome", outcome).register(registry).record(duration);
        }
    }

    public void cursorReset(String integrationId, EmailChannelSettings settings) {
        if (registry != null) {
            Counter.builder(CURSOR_RESETS).description("Mailbox cursor resets (UIDVALIDITY changed)").tag("integration", integrationId)
                    .tag("transport", transport(settings)).tag("mode", mode(settings)).register(registry).increment();
        }
    }

    /**
     * What marking a processed mail did: flagged, moved, flagged_not_moved, nothing
     * (the server takes no keywords and no move was asked for).
     */
    public void marked(String integrationId, EmailChannelSettings settings, String result) {
        if (registry != null) {
            Counter.builder(MARKED).description("Processed mail marked in the mailbox, by what the server allowed").tag("integration", integrationId)
                    .tag("transport", transport(settings)).tag("mode", mode(settings)).tag("result", result).register(registry).increment();
        }
    }

    public void stranded(String outcome) {
        if (registry != null) {
            Counter.builder(STRANDED).description("Submitted email turns resolved by the stranded-turn sweep").tag("outcome", outcome)
                    .register(registry).increment();
        }
    }

    /**
     * One terminal (or retry) outcome of a conversation-mode reply:
     * {@code eddi.email.outbound{kind=reply, outcome}} — the counter the HITL and
     * owner notifiers share, with their tag set.
     */
    public void reply(String outcome) {
        if (registry != null) {
            registry.counter(OUTBOUND, "kind", "reply", "outcome", outcome).increment();
        }
    }

    /**
     * From the reply's ledger row being written to the relay accepting it, retries
     * included.
     */
    public void replyLatency(Duration duration) {
        if (registry != null) {
            Timer.builder(OUTBOUND_LATENCY).description("From a reply being queued to the relay accepting it").tag("kind", "reply")
                    .register(registry).record(duration);
        }
    }

    /** An address added to an integration's suppression list. */
    public void suppressed(String reason) {
        if (registry != null) {
            Counter.builder(SUPPRESSIONS).description("Addresses added to an email integration's suppression list").tag("reason", reason)
                    .register(registry).increment();
        }
    }
}
