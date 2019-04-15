package ai.labs.models;

import ai.labs.models.Deployment.Environment;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static ai.labs.models.Deployment.Status;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class BotDeploymentStatus {
    private Environment environment = Environment.unrestricted;
    private String botId;
    private Integer botVersion;
    private Status status = Status.NOT_FOUND;
    private DocumentDescriptor descriptor;
}
