/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import ai.labs.eddi.engine.model.Deployment.Environment;

import java.util.HashMap;
import java.util.Map;

public class AgentDeployment {
    private Environment environment = Environment.production;
    private String agentId;
    private Map<String, Context> initialContext = new HashMap<>();

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public String getAgentId() {
        return agentId;
    }

    /** Also accepts {@code botId}, what EDDI 5 stored in a trigger. */
    @JsonAlias("botId")
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Map<String, Context> getInitialContext() {
        return initialContext;
    }

    public void setInitialContext(Map<String, Context> initialContext) {
        this.initialContext = initialContext;
    }
}
