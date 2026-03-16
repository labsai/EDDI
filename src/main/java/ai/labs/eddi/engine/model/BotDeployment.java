package ai.labs.eddi.engine.model;

import ai.labs.eddi.engine.model.Deployment.Environment;

import java.util.HashMap;
import java.util.Map;

public class BotDeployment {
    private Environment environment = Environment.unrestricted;
    private String botId;
    private Map<String, Context> initialContext = new HashMap<>();

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public Map<String, Context> getInitialContext() {
        return initialContext;
    }

    public void setInitialContext(Map<String, Context> initialContext) {
        this.initialContext = initialContext;
    }
}
