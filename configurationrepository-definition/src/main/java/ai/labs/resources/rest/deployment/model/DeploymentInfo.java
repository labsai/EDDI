package ai.labs.resources.rest.deployment.model;

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

    public enum Environment {
        test,
        restricted,
        unrestricted
    }
}
