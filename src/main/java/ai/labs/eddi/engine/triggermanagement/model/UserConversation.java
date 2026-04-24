/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.model;

import ai.labs.eddi.engine.model.Deployment;

public class UserConversation {
    private String intent;
    private String userId;
    private Deployment.Environment environment;
    private String agentId;
    private String conversationId;

    public UserConversation() {
    }

    public UserConversation(String intent, String userId, Deployment.Environment environment, String agentId, String conversationId) {
        this.intent = intent;
        this.userId = userId;
        this.environment = environment;
        this.agentId = agentId;
        this.conversationId = conversationId;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Deployment.Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Deployment.Environment environment) {
        this.environment = environment;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
