package ai.labs.eddi.configs.deployment.model;

import ai.labs.eddi.engine.model.Deployment.Environment;
import lombok.Getter;
import lombok.Setter;

/**
 * @author ginccc
 */
@Getter
@Setter
public class DeploymentInfo {
    private String botId;
    private Integer botVersion;
    private Environment environment;
    private DeploymentStatus deploymentStatus;

    public enum DeploymentStatus {
        deployed,
        undeployed
    }
}
