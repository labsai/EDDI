/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import ai.labs.eddi.engine.model.Deployment;

public class UserConversation {
    private String intent;
    private String userId;
    private Deployment.Environment environment;
    private String agentId;
    private String conversationId;
    private String channelSigner;

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

    /** Also accepts {@code botId}, what EDDI 5 stored. */
    @JsonAlias("botId")
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * For a mapping a channel adapter wrote: a reference to the app (integration or
     * legacy connector) whose credentials started the conversation — for Slack
     * {@code integration:<name>} or {@code legacy:<channelId>}. It lets a reply
     * that is posted later without an inbound event (the outcome of an approval in
     * a DM) use exactly that app's bot token. Not a credential. {@code null} for
     * every other mapping and for one an earlier release stored: "unknown".
     */
    public String getChannelSigner() {
        return channelSigner;
    }

    public void setChannelSigner(String channelSigner) {
        this.channelSigner = channelSigner;
    }
}
