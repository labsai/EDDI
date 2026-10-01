/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.api.IRestAgentUsage;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore.ConversationUsage;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @see IRestAgentUsage
 */
@ApplicationScoped
public class RestAgentUsage implements IRestAgentUsage {

    private final ResourceAccessGuard accessGuard;
    private final IConversationMemoryStore conversationMemoryStore;

    @Inject
    public RestAgentUsage(ResourceAccessGuard accessGuard, IConversationMemoryStore conversationMemoryStore) {
        this.accessGuard = accessGuard;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    @Override
    public ConversationUsage readUsage(String agentId) {
        accessGuard.requireAccess(agentId, AccessLevel.VIEW, "agent");
        return conversationMemoryStore.getConversationUsage(agentId);
    }
}
