/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.Deployment;

import java.util.List;

/**
 * The deployed-agent set, <em>unscoped</em> — every latest deployed agent in an
 * environment with its raw descriptor.
 * <p>
 * For engine code that routes traffic rather than answering a caller: the
 * channel router, which must see every agent a legacy Slack connector names no
 * matter who (if anyone) is on the other end of the webhook that triggered its
 * refresh.
 * <p>
 * Never hand this list to a caller. The REST and MCP listings go through
 * {@link IRestAgentAdministration#getDeploymentStatuses}, which drops what the
 * caller may not use and redacts the grant list and access index from what is
 * left.
 */
public interface IDeploymentStatusReader {

    /**
     * @param environment
     *            the environment to list
     * @return every latest deployed agent, newest first, descriptors unredacted
     */
    List<AgentDeploymentStatus> readAllDeploymentStatuses(Deployment.Environment environment);
}
