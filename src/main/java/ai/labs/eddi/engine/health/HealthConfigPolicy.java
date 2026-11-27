/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.HealthConfig;
import ai.labs.eddi.datastore.IResourceStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The {@link HealthConfig} of one agent version, read once per version and
 * cached — versions are immutable — like {@code ConversationRatingPolicy}. A
 * version without one or an unknown version yields the defaults; a read failure
 * yields them too ({@link #of}) or throws ({@link #require}, the outcome
 * evaluation, which retries) and is never cached, so a store that blinked does
 * not decide outcomes for half an hour.
 * <p>
 * Lenient on purpose: an agent that reached the store without the save-time
 * checks ({@link HealthConfigValidator}) — written by hand, or by an import
 * before imports ran them — has its unknown outcome values ignored and its
 * out-of-range windows replaced by the deployment's.
 */
@ApplicationScoped
public class HealthConfigPolicy {

    private static final Logger LOGGER = Logger.getLogger(HealthConfigPolicy.class);

    /**
     * The settings of one version, resolved.
     *
     * @param outcomes
     *            action → declared outcome, only valid entries
     * @param windowHours
     *            origin → window override in hours, only valid entries
     */
    public record Resolved(Map<String, String> outcomes, boolean assumeResolvedOnSilence, Map<String, Integer> windowHours,
            boolean selfReportTool) {

        public static final Resolved DEFAULTS = new Resolved(Map.of(), true, Map.of(), false);

        public Resolved {
            outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
            windowHours = windowHours == null ? Map.of() : Map.copyOf(windowHours);
        }

        /** Resolves a stored configuration; {@code null} gives the defaults. */
        public static Resolved of(HealthConfig config) {
            if (config == null) {
                return DEFAULTS;
            }
            Map<String, String> outcomes = new LinkedHashMap<>();
            if (config.getOutcomes() != null) {
                config.getOutcomes().forEach((action, outcome) -> {
                    if (action != null && !action.isBlank() && outcome != null && HealthConfig.DECLARABLE_OUTCOMES.contains(outcome)
                            && !HealthConfigValidator.UNMAPPABLE_ACTIONS.contains(action)) {
                        outcomes.put(action, outcome);
                    }
                });
            }
            Map<String, Integer> windows = new LinkedHashMap<>();
            if (config.getInactivityWindowHours() != null) {
                config.getInactivityWindowHours().forEach((origin, hours) -> {
                    if (origin != null && HealthOrigins.ALL.contains(origin) && hours != null && hours >= HealthConfigValidator.MIN_WINDOW_HOURS
                            && hours <= HealthConfigValidator.MAX_WINDOW_HOURS) {
                        windows.put(origin, hours);
                    }
                });
            }
            return new Resolved(outcomes, config.isAssumeResolvedOnSilence(), windows, config.isSelfReportTool());
        }
    }

    private final IAgentStore agentStore;
    private final HealthSettings settings;

    /** id:version → resolved setting. */
    private final Cache<String, Resolved> byVersion = Caffeine.newBuilder()
            // After write, for the reason ConversationReviewPolicy gives: an id
            // re-imported after a permanent delete must be looked at again.
            .expireAfterWrite(Duration.ofMinutes(30)).maximumSize(10_000).build();

    @Inject
    public HealthConfigPolicy(IAgentStore agentStore, HealthSettings settings) {
        this.agentStore = agentStore;
        this.settings = settings;
    }

    /**
     * The resolved settings of one agent version; the defaults when unknown or when
     * the store cannot be read — for callers that must answer anyway (the
     * self-report tool, the drill-down).
     */
    public Resolved of(String agentId, Integer version) {
        try {
            return require(agentId, version);
        } catch (HealthStoreException e) {
            LOGGER.debugf("Could not read agent %s v%s for its health setting: %s", sanitize(agentId), version, sanitize(e.getMessage()));
            return Resolved.DEFAULTS;
        }
    }

    /**
     * As {@link #of}, but a store that cannot be read throws instead of yielding
     * the defaults — what the outcome evaluation uses, so a session is never
     * judged, and stored as final, under settings that were merely unavailable. An
     * absent version still yields the defaults.
     *
     * @throws HealthStoreException
     *             when the agent store fails (not cached: the next call reads
     *             again)
     */
    public Resolved require(String agentId, Integer version) {
        if (agentId == null || version == null) {
            return Resolved.DEFAULTS;
        }
        Resolved known = byVersion.get(agentId + ":" + version, key -> {
            try {
                AgentConfiguration configuration = agentStore.read(agentId, version);
                return Resolved.of(configuration == null ? null : configuration.getHealthConfig());
            } catch (IResourceStore.ResourceNotFoundException e) {
                // Absent, not unavailable: the defaults — null is not cached, so an id
                // re-imported later is read again.
                return null;
            } catch (Exception e) {
                throw new HealthStoreException("Could not read the health setting of an agent version", e);
            }
        });
        return known == null ? Resolved.DEFAULTS : known;
    }

    /**
     * The inactivity window of a session of {@code origin} under {@code resolved}:
     * the agent's override, else the deployment's — e-mail its own, every other
     * origin the general one.
     */
    public Duration windowOf(String origin, Resolved resolved) {
        Integer hours = resolved == null ? null : resolved.windowHours().get(origin);
        if (hours != null) {
            return Duration.ofHours(hours);
        }
        return HealthOrigins.EMAIL.equals(origin) ? settings.emailSessionWindow() : settings.sessionWindow();
    }

    /** Whether the version offers {@code report_conversation_outcome}. */
    public boolean selfReportToolEnabled(String agentId, Integer version) {
        return of(agentId, version).selfReportTool();
    }

    /** For tests: whether a version's setting is cached. */
    Optional<Resolved> cached(String agentId, Integer version) {
        return Optional.ofNullable(byVersion.getIfPresent(agentId + ":" + version));
    }
}
