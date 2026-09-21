/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.model;

import ai.labs.eddi.engine.model.Deployment.Environment;
import java.util.Objects;

/**
 * @author ginccc
 */
public class DeploymentInfo {
    private String agentId;
    private Integer agentVersion;
    private Environment environment;
    private DeploymentStatus deploymentStatus;

    public enum DeploymentStatus {
        deployed, undeployed
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Integer agentVersion) {
        this.agentVersion = agentVersion;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public DeploymentStatus getDeploymentStatus() {
        return deploymentStatus;
    }

    public void setDeploymentStatus(DeploymentStatus deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DeploymentInfo that))
            return false;
        return Objects.equals(agentId, that.agentId)
                && Objects.equals(agentVersion, that.agentVersion)
                && environment == that.environment;
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentId, agentVersion, environment);
    }
}
