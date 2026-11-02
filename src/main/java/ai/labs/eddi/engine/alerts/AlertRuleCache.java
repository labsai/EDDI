/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertRule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The enabled rules watching each agent, cached so the alert worker does not
 * query the rule store for every rating and failed turn.
 * <p>
 * A save or delete on this node invalidates the whole cache at once (a rule
 * over every agent touches every entry, so per-agent invalidation would not be
 * simpler). Another replica's change is picked up when the entry expires —
 * {@link #TTL}. Read only on the alert worker, never on a request or turn
 * thread.
 */
@ApplicationScoped
public class AlertRuleCache {

    static final Duration TTL = Duration.ofSeconds(60);

    private static final String NO_AGENT = "";

    private final IAlertRuleStore ruleStore;
    private final Cache<String, List<AlertRule>> rulesByAgent;
    private final Cache<String, OutputNeeds> outputNeedsByAgent;

    @Inject
    public AlertRuleCache(IAlertRuleStore ruleStore) {
        this.ruleStore = ruleStore;
        this.rulesByAgent = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();
        this.outputNeedsByAgent = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();
    }

    /**
     * What the agent's OUTPUT_MATCH rules read — from the cache only, never
     * loading: this is asked on the turn thread. Empty on a miss.
     */
    public Optional<OutputNeeds> cachedOutputNeeds(String agentId) {
        return Optional.ofNullable(outputNeedsByAgent.getIfPresent(agentId == null ? NO_AGENT : agentId));
    }

    /**
     * Loads (on the alert worker) and caches what the agent's OUTPUT_MATCH rules
     * read.
     */
    public OutputNeeds loadOutputNeeds(String agentId) {
        OutputNeeds needs = OutputNeeds.of(rulesFor(agentId));
        outputNeedsByAgent.put(agentId == null ? NO_AGENT : agentId, needs);
        return needs;
    }

    /** The enabled rules whose scope covers {@code agentId}. */
    public List<AlertRule> rulesFor(String agentId) {
        String key = agentId == null ? NO_AGENT : agentId;
        return rulesByAgent.get(key, id -> List.copyOf(ruleStore.findEnabledForAgent(id.isEmpty() ? null : id)));
    }

    public void invalidate() {
        rulesByAgent.invalidateAll();
        outputNeedsByAgent.invalidateAll();
    }
}
