/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Persistence for {@link AgentTestSuite} documents — versioned configuration,
 * like every other config resource.
 *
 * @since 6.5.0
 */
public interface IAgentTestSuiteStore extends IResourceStore<AgentTestSuite> {
}
