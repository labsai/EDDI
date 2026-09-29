/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;

/**
 * Bridge between the conversation engine and user-scoped storage. Provides the
 * {@link IUserMemoryStore} and optional
 * {@link AgentConfiguration.UserMemoryConfig} for advanced features.
 */
public interface IPropertiesHandler {

    /**
     * User memory store instance. Non-null when provided by ConversationService.
     */
    IUserMemoryStore getUserMemoryStore();

    /**
     * User memory config from agent configuration — recall settings, default
     * visibility, guardrails. {@code null} when the agent declares no
     * {@code userMemoryConfig} and does not enable the memory tools.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return null;
    }

    /**
     * Whether the LLM memory tools are enabled ({@code enableMemoryTools}).
     * Defaults to "a config is present" — the meaning the config alone used to
     * carry.
     */
    default boolean isMemoryToolsEnabled() {
        return getUserMemoryConfig() != null;
    }

    /**
     * Verifies a {@code groupId} found only on an earlier step before the
     * {@code longTerm} boundary scopes group-visible properties to it — see
     * {@link ConversationGroups#resolveGroupIds(IConversationMemory, ConversationGroups.MembershipCheck)}.
     * {@code null} (the default) trusts only the current step's {@code groupId}.
     */
    default ConversationGroups.MembershipCheck getGroupMembershipCheck() {
        return null;
    }

    /** The userId this handler is scoped to. */
    String getUserId();

    /**
     * Attachment blob store, used at conversation init to resolve server-side
     * metadata for {@code storageRef}-only attachment references. {@code null} when
     * no store is configured.
     */
    default IAttachmentStore getAttachmentStore() {
        return null;
    }

    /**
     * Per-turn cap on the number of attachments forwarded to the LLM. Defaults to
     * {@link ai.labs.eddi.engine.memory.AttachmentContextExtractor#DEFAULT_MAX_ATTACHMENTS_PER_TURN}.
     */
    default int getMaxAttachmentsPerTurn() {
        return AttachmentContextExtractor.DEFAULT_MAX_ATTACHMENTS_PER_TURN;
    }
}
