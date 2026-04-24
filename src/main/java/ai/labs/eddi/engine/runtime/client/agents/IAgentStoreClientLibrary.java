/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.agents;

import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.service.ServiceException;

/**
 * @author ginccc
 */
public interface IAgentStoreClientLibrary {
    IAgent getAgent(String agentId, Integer version) throws ServiceException, IllegalAccessException;
}
