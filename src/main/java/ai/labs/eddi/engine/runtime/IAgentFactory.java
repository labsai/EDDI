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
     * Removes a deployed agent from this node's runtime registry.
     *
     * @param version
     *            the exact version to undeploy, or {@code null} for <em>every</em>
     *            deployed version of {@code agentId}. The null form is what the
     *            dynamic-agent teardown paths use: they know the agent id they
     *            created but never carry its version, and "this agent is gone" has
     *            to mean all of it. Passing null used to build an
     *            {@code AgentId(id, null)} that equalled no deployed key, so the
     *            removal silently did nothing while the caller logged success.
     */
    void undeployAgent(Deployment.Environment environment, String agentId, Integer version) throws ServiceException, IllegalAccessException;

    interface DeploymentProcess {
        void completed(Deployment.Status status);
    }
}
