package ai.labs.eddi.models;

import ai.labs.eddi.models.Deployment.Environment;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static ai.labs.eddi.models.Deployment.Status;

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
