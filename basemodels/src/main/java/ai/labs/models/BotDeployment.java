package ai.labs.models;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BotDeployment {
    private Deployment.Environment environment;
    private String botId;
    private Context initialContext;
}
