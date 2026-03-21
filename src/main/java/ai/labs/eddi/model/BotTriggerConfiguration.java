package ai.labs.eddi.model;


import ai.labs.eddi.engine.model.BotDeployment;

import java.util.LinkedList;
import java.util.List;

public class BotTriggerConfiguration {
    private String intent;
    private List<BotDeployment> botDeployments = new LinkedList<>();

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public List<BotDeployment> getBotDeployments() {
        return botDeployments;
    }

    public void setBotDeployments(List<BotDeployment> botDeployments) {
        this.botDeployments = botDeployments;
    }
}
