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

    /**
     * Removes a deployed agent from {@code environment}. A {@code null}
     * {@code version} undeploys EVERY deployed version of {@code agentId} — the
     * contract the dynamic-agent teardown callers rely on, since they know an agent
     * only by id.
     *
     * @return the number of deployed agent versions actually removed; {@code 0}
     *         means nothing was deployed under that id, which callers reporting
     *         success to a user or an LLM must not describe as a teardown
     */
    int undeployAgent(Deployment.Environment environment, String agentId, Integer version) throws ServiceException, IllegalAccessException;

    interface DeploymentProcess {
        void completed(Deployment.Status status);
    }
}
