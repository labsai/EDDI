/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    public List<AgentDeployment> getAgentDeployments() {
        return agentDeployments;
    }

    public void setAgentDeployments(List<AgentDeployment> agentDeployments) {
        this.agentDeployments = agentDeployments;
    }
}
