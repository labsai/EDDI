package ai.labs.eddi.engine.model;

import ai.labs.eddi.engine.model.Deployment.Environment;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class BotDeployment {
    private Environment environment = Environment.unrestricted;
    private String botId;
    private Map<String, Context> initialContext = new HashMap<>();
}
