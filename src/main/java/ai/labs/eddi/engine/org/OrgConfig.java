/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies {@code eddi.org.mode} and {@code eddi.org.resolver} at startup, ahead
 * of every other startup observer, and wires {@link OrgContext}'s counters into
 * Micrometer.
 * <p>
 * This release supports single mode only. {@code eddi.org.mode=multi} is
 * refused: its preconditions (the enablement marker, forced SSRF, the platform
 * roles and the rest of plan §5.1) do not exist yet, and starting without them
 * would be a shared deployment with no isolation.
 */
@ApplicationScoped
public class OrgConfig {

    private static final Logger LOGGER = Logger.getLogger(OrgConfig.class);

    static final String FALLBACK_METRIC = "eddi.org.context.fallback";
    static final String PLATFORM_METRIC = "eddi.org.platform.scope";
    /**
     * Distinct platform-scope reasons tagged before the rest are counted as
     * {@code other}.
     */
    static final int MAX_REASON_TAGS = 100;

    private final String mode;
    private final OrgResolution resolution;
    private final MeterRegistry meterRegistry;

    @Inject
    public OrgConfig(@ConfigProperty(name = "eddi.org.mode", defaultValue = "single") String mode, OrgResolution resolution,
            MeterRegistry meterRegistry) {
        this.mode = mode;
        this.resolution = resolution;
        this.meterRegistry = meterRegistry;
    }

    void onStart(@Observes
    @Priority(Integer.MIN_VALUE) StartupEvent event) {
        apply();
    }

    /** Validates the configuration and configures {@link OrgContext}. */
    void apply() {
        OrgMode parsed = parseMode(mode);
        if (parsed == OrgMode.MULTI) {
            throw new IllegalStateException("eddi.org.mode=multi is not available in this EDDI release. Organizations are being built "
                    + "in phases; until the enablement migration ships, only eddi.org.mode=single is supported.");
        }
        if (!SingleOrgResolver.NAME.equals(resolution.resolverName())) {
            throw new IllegalStateException("eddi.org.resolver=" + resolution.resolverName() + " is not available in this EDDI release; "
                    + "only '" + SingleOrgResolver.NAME + "' is supported.");
        }
        resolution.resolver();
        OrgContext.configure(parsed, new MetricsObserver(meterRegistry));
        LOGGER.debugf("Organizations: mode=%s, resolver=%s", parsed, resolution.resolverName());
    }

    static OrgMode parseMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "single" -> OrgMode.SINGLE;
            case "multi" -> OrgMode.MULTI;
            default -> throw new IllegalStateException("eddi.org.mode must be 'single' or 'multi', not '" + value + "'");
        };
    }

    /** Counts fallbacks per call site and platform scopes per reason. */
    static final class MetricsObserver implements OrgContextObserver {
        private final MeterRegistry registry;
        private final Map<String, Counter> fallbacks = new ConcurrentHashMap<>();
        private final Map<String, Counter> platformScopes = new ConcurrentHashMap<>();

        MetricsObserver(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void fallback(String site) {
            fallbacks.computeIfAbsent(site, s -> Counter.builder(FALLBACK_METRIC)
                    .description("Single-mode org lookups that found no binding and used the default org; expected to stay 0")
                    .tag("site", s).register(registry)).increment();
        }

        @Override
        public void platformScope(String reason) {
            String tag = platformScopes.containsKey(reason) || platformScopes.size() < MAX_REASON_TAGS ? reason : OrgContext.OVERFLOW_TAG;
            platformScopes.computeIfAbsent(tag, r -> Counter.builder(PLATFORM_METRIC).description("Entries into OrgScope.platform")
                    .tag("reason", r).register(registry)).increment();
        }
    }
}
