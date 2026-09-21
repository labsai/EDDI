/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;

import java.util.List;

/**
 * Everything a {@link ToolSourceProvider} needs to decide what it contributes
 * this turn (R2 step 1). Carries what {@code AgentOrchestrator#buildToolSetup}
 * already assembles before tool discovery: the conversation memory, the
 * configured task, its built-in-tools whitelist, the resolved dynamic-agent
 * config, and the caller's identity — plus, for the group-aware providers Wave
 * 2 adds (I5's {@code GroupTaskToolsProvider}, I17's
 * {@code ArtifactToolsProvider}), the group context vars already injected into
 * member conversations.
 *
 * @param memory
 *            the live conversation memory for this turn
 * @param task
 *            the configured LLM task driving this turn
 * @param builtInToolsWhitelist
 *            {@code task.getBuiltInToolsWhitelist()}, or {@code null} when
 *            unset. Null and empty mean the same thing — no whitelist, so
 *            providers fall back to their own default enablement rule — and
 *            both are handled here rather than assumed normalized upstream,
 *            because the live path treats them identically
 *            ({@code whitelist != null && !whitelist.isEmpty()}) and a caller
 *            passing a task's raw getter must not behave differently from one
 *            that normalized first. Use {@link #hasNoWhitelist()} rather than
 *            null-checking this field directly.
 * @param dynamicAgentConfig
 *            resolved once per turn and shared by every provider that reads it,
 *            so they all see one consistent snapshot. Never {@code null} — the
 *            compact constructor substitutes a default (dynamic agents
 *            disabled) for a null argument, so a provider can dereference this
 *            without a guard. That is enforced here rather than merely
 *            documented: the promise was previously Javadoc-only, and callers
 *            (tests among them) do pass null.
 * @param userId
 *            the authenticated caller's user id
 * @param agentId
 *            the agent running this turn (the parent, for dynamic-agent
 *            purposes)
 * @param groupConversationId
 *            the {@code groupConversationId} context var, or {@code null}
 *            outside a group discussion
 */
public record ToolAssemblyContext(IConversationMemory memory, LlmConfiguration.Task task,
        List<String> builtInToolsWhitelist, DynamicAgentConfig dynamicAgentConfig, String userId, String agentId,
        String groupConversationId) {

    /**
     * Normalizes {@code dynamicAgentConfig} so the record's own contract holds at
     * one choke point instead of at every provider that reads it. A default
     * {@link DynamicAgentConfig} is disabled, which is the correct meaning of "no
     * config supplied" — the same reading {@code AgentOrchestrator} applies.
     */
    public ToolAssemblyContext {
        if (dynamicAgentConfig == null) {
            dynamicAgentConfig = new DynamicAgentConfig();
        }
    }

    /**
     * @return true if a whitelist is configured and names this tool key; false when
     *         no whitelist is configured (nothing is excluded) or the key is absent
     *         from a configured one
     */
    public boolean isWhitelisted(String toolKey) {
        return builtInToolsWhitelist != null && builtInToolsWhitelist.contains(toolKey);
    }

    /**
     * @return true if no whitelist is configured — every default-enabled tool
     *         applies
     */
    public boolean hasNoWhitelist() {
        return builtInToolsWhitelist == null || builtInToolsWhitelist.isEmpty();
    }
}
