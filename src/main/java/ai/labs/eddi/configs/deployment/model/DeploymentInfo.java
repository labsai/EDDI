package ai.labs.eddi.configs.deployment.model;

import ai.labs.eddi.engine.model.Deployment.Environment;

/**
 * @author ginccc
 */
public class DeploymentInfo {
    private String botId;
    private Integer botVersion;
    private Environment environment;
    private DeploymentStatus deploymentStatus;

    public enum DeploymentStatus {
        deployed,
        undeployed
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public Integer getBotVersion() {
        return botVersion;
    }

    public void setBotVersion(Integer botVersion) {
        this.botVersion = botVersion;
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
