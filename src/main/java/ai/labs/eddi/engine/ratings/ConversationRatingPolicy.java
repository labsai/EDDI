/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The rating setting of one agent version — see {@link RatingConfig}. Mirrors
 * {@code ConversationReviewPolicy}: the decision is made against the agent
 * <em>version the conversation runs on</em>, read once per version and cached,
 * because versions are immutable.
 * <p>
 * A setting that could not be read is treated as off and is <em>not</em>
 * cached, so a store that blinked refuses ratings for that one request rather
 * than for half an hour.
 */
@ApplicationScoped
public class ConversationRatingPolicy {

    private static final Logger LOGGER = Logger.getLogger(ConversationRatingPolicy.class);

    private final IAgentStore agentStore;

    /** id:version → enabled rating setting, or empty when off. */
    private final Cache<String, Optional<RatingConfig>> ratingByVersion = Caffeine.newBuilder()
            // After write, for the reason ConversationReviewPolicy gives: an id
            // re-imported after a permanent delete must be looked at again.
            .expireAfterWrite(Duration.ofMinutes(30)).maximumSize(10_000).build();

    @Inject
    public ConversationRatingPolicy(IAgentStore agentStore) {
        this.agentStore = agentStore;
    }

    /**
     * The rating setting of one agent version, when ratings are enabled on it;
     * empty when they are off, the version is unknown, or it could not be read.
     */
    public Optional<RatingConfig> ratingConfigOf(String agentId, Integer version) {
        if (agentId == null || version == null) {
            return Optional.empty();
        }
        Optional<RatingConfig> known = ratingByVersion.get(agentId + ":" + version, key -> {
            try {
                AgentConfiguration configuration = agentStore.read(agentId, version);
                RatingConfig rating = configuration == null ? null : configuration.getRatingConfig();
                return rating != null && rating.isEnabled() ? Optional.of(rating) : Optional.empty();
            } catch (Exception e) {
                LOGGER.warnf("Could not read agent %s v%s for its rating setting: %s", sanitize(agentId), version, sanitize(e.getMessage()));
                // null is not cached: the next request reads the store again.
                return null;
            }
        });
        return known == null ? Optional.empty() : known;
    }
}
