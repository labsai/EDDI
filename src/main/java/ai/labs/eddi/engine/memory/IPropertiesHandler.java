package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;

/**
 * Bridge between the conversation engine and user-scoped storage. Provides the
 * {@link IUserMemoryStore} and optional
 * {@link AgentConfiguration.UserMemoryConfig} for advanced features.
 */
public interface IPropertiesHandler {

    /** User memory store instance — always non-null. */
    IUserMemoryStore getUserMemoryStore();

    /**
     * User memory config from agent configuration. {@code null} when advanced
     * memory tools (Dream, guardrails, recall settings) are not enabled.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return null;
    }

    /** The userId this handler is scoped to. */
    String getUserId();
}
