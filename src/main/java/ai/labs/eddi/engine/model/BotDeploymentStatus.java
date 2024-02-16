package ai.labs.eddi.engine.model;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static ai.labs.eddi.engine.model.Deployment.Status;

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
