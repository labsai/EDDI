/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Reads an agent's {@code hitlConfig} (and its {@code oversight} part) for the
 * oversight services, through the shared per-version configuration cache — an
 * agent version is immutable, so a cached one is never stale.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OversightConfigLookup {

    private static final Logger LOGGER = Logger.getLogger(OversightConfigLookup.class);

    private final AgentConfigurationCache configCache;
    private final IAgentStore agentStore;

    @Inject
    public OversightConfigLookup(AgentConfigurationCache configCache, IAgentStore agentStore) {
        this.configCache = configCache;
        this.agentStore = agentStore;
    }

    /** The agent document at a version; null when unreadable. */
    public AgentConfiguration agent(String agentId, Integer version) {
        if (agentId == null) {
            return null;
        }
        try {
            int v = version != null ? version : currentVersion(agentId);
            return configCache.read(agentId, v);
        } catch (Exception e) {
            LOGGER.debugf("Could not read agent %s v%s for oversight: %s", sanitize(agentId), version, e.getMessage());
            return null;
        }
    }

    /**
     * The agent's {@code hitlConfig} at a version (null = newest); null when none.
     */
    public AgentConfiguration.HitlConfig hitlConfig(String agentId, Integer version) {
        AgentConfiguration agent = agent(agentId, version);
        return agent != null ? agent.getHitlConfig() : null;
    }

    /** The agent's {@code hitlConfig.oversight} at a version; null when none. */
    public OversightConfig oversight(String agentId, Integer version) {
        AgentConfiguration.HitlConfig hitl = hitlConfig(agentId, version);
        return hitl != null ? hitl.getOversight() : null;
    }

    /** The agent's newest stored version. */
    public int currentVersion(String agentId) throws Exception {
        var resourceId = agentStore.getCurrentResourceId(agentId);
        if (resourceId == null) {
            throw new IResourceStore.ResourceNotFoundException("Agent not found: " + agentId);
        }
        return resourceId.getVersion();
    }
}
