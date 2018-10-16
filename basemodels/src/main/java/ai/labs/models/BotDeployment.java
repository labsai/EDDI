package ai.labs.models;

import ai.labs.models.Deployment.Environment;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BotDeployment {
    private Environment environment = Environment.unrestricted;
    private String botId;
    private Context initialContext;
}
