/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Store interface for group configurations. Uses the DB-agnostic
 * {@code AbstractResourceStore} via {@code IResourceStorageFactory}.
 *
 * @author ginccc
 */
public interface IAgentGroupStore extends IResourceStore<AgentGroupConfiguration> {
}
