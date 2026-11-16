/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

/**
 * Keeps agents the engine created at runtime out of a discussion's recruits.
 * <p>
 * {@code create_sub_agent} deploys an agent with a system prompt the creating
 * member wrote, and a recruit votes with full weight and counts toward quorum.
 * Recruiting one's own creation would therefore be a way to add voters that say
 * what their creator wants. Both recruitment paths — the {@code recruit_agent}
 * tool and the facilitator's RECRUIT move — refuse an id on the discussion's
 * {@code createdAgentIds} and any agent whose configuration carries a
 * {@code dynamicOrigin}, wherever it was created.
 *
 * @since 6.7.0
 */
public final class CreatedAgentRecruitGuard {

    private static final Logger LOGGER = Logger.getLogger(CreatedAgentRecruitGuard.class);

    /** The refusal, with the agent id as its one {@code %s}. */
    public static final String REFUSAL = "Agent '%s' was created during a discussion, so it cannot be recruited: "
            + "a recruit votes and counts toward quorum, and an agent a member created would be an extra voice for that member. "
            + "Recruit an agent that was deployed independently.";

    private CreatedAgentRecruitGuard() {
    }

    /**
     * Whether the agent's current configuration carries a {@code dynamicOrigin}. An
     * agent that does not exist is not one (the deployment check refuses it
     * anyway); any other read failure counts as one, so an unverifiable recruit is
     * refused rather than admitted.
     */
    public static boolean isDynamicAgent(IAgentStore agentStore, String agentId) {
        if (agentStore == null || agentId == null) {
            return false;
        }
        try {
            IResourceStore.IResourceId current = agentStore.getCurrentResourceId(agentId);
            AgentConfiguration configuration = current != null ? agentStore.read(agentId, current.getVersion()) : null;
            return configuration != null && configuration.getDynamicOrigin() != null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOGGER.debugf("Could not read agent '%s' to check for a dynamic origin — refusing it as a recruit: %s",
                    LogSanitizer.sanitize(agentId), e.getMessage());
            return true;
        }
    }
}
