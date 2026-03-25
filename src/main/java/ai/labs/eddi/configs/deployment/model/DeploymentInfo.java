package ai.labs.eddi.configs.deployment.model;

import ai.labs.eddi.engine.model.Deployment.Environment;

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
}
