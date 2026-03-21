package ai.labs.eddi.engine.triggermanagement.model;


import ai.labs.eddi.engine.model.AgentDeployment;

import java.util.LinkedList;
import java.util.List;

public class AgentTriggerConfiguration {
    private String intent;
    private List<AgentDeployment> agentDeployments = new LinkedList<>();

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public List<AgentDeployment> getBotDeployments() {
        return agentDeployments;
    }

    public void setBotDeployments(List<AgentDeployment> agentDeployments) {
        this.agentDeployments = agentDeployments;
    }
}
