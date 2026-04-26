/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.model.Deployment;

import java.util.List;

/**
 * @author ginccc
 */
public interface IAgentFactory {
    IAgent getLatestAgent(Deployment.Environment environment, String agentId) throws ServiceException;

    IAgent getLatestReadyAgent(Deployment.Environment environment, String agentId) throws ServiceException;

    List<IAgent> getAllLatestAgents(Deployment.Environment environment) throws ServiceException;

    IAgent getAgent(Deployment.Environment environment, String agentId, Integer version) throws ServiceException;

    void deployAgent(Deployment.Environment environment, String agentId, Integer version, DeploymentProcess deploymentProcess)
            throws ServiceException, IllegalAccessException;

    void undeployAgent(Deployment.Environment environment, String agentId, Integer version) throws ServiceException, IllegalAccessException;

    interface DeploymentProcess {
        void completed(Deployment.Status status);
    }
}
