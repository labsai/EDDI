/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Persistence store for multi-agent group configurations (members, discussion
 * style, phases). {@code GroupConversationService} and the group REST API load
 * these to run structured discussions.
 */
public interface IAgentGroupStore extends IResourceStore<AgentGroupConfiguration> {
}
