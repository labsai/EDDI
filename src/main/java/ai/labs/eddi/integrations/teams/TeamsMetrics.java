/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * Counters of the Teams adapter:
 * <ul>
 * <li>{@code eddi_teams_requests_refused_total{reason}}: requests whose Bot
 * Connector token failed a check ({@link TeamsTokenVerifier.Outcome}, lower
 * case).</li>
 * <li>{@code eddi_teams_activities_total{outcome}}: verified activities by what
 * became of them ({@code handled}, {@code duplicate}, {@code no_integration},
 * {@code tenant_refused}, {@code identity_mismatch}, {@code targeted_ignored},
 * {@code bot_removed}).</li>
 * </ul>
 * Both tags take a fixed set of values, so a request cannot grow the number of
 * series.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsMetrics {

    public static final String HANDLED = "handled";
    public static final String DUPLICATE = "duplicate";
    public static final String NO_INTEGRATION = "no_integration";
    public static final String TENANT_REFUSED = "tenant_refused";
    public static final String IDENTITY_MISMATCH = "identity_mismatch";
    public static final String TARGETED_IGNORED = "targeted_ignored";
    public static final String BOT_REMOVED = "bot_removed";

    private final MeterRegistry meterRegistry;

    @Inject
    public TeamsMetrics(Instance<MeterRegistry> meterRegistry) {
        this(meterRegistry != null && meterRegistry.isResolvable() ? meterRegistry.get() : null);
    }

    TeamsMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Counters that count nothing, for code built outside CDI. */
    public static TeamsMetrics none() {
        return new TeamsMetrics((MeterRegistry) null);
    }

    public void refused(TeamsTokenVerifier.Outcome outcome) {
        if (meterRegistry != null && outcome != null) {
            meterRegistry.counter("eddi_teams_requests_refused_total", "reason", outcome.name().toLowerCase(Locale.ROOT)).increment();
        }
    }

    public void activity(String outcome) {
        if (meterRegistry != null && outcome != null) {
            meterRegistry.counter("eddi_teams_activities_total", "outcome", outcome).increment();
        }
    }
}
