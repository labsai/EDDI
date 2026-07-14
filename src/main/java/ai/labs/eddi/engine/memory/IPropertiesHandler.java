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
     * User memory config from agent configuration. {@code null} when advanced
     * memory tools (Dream, guardrails, recall settings) are not enabled.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
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
