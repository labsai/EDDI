/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;

/**
 * @author ginccc
 */
public interface IAgentStoreService {
    AgentConfiguration getAgentConfiguration(String agentId, Integer version) throws ServiceException;
}
