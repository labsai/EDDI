/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.descriptor.model;

import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.configs.descriptors.model.ResourceDescriptor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * @author ginccc
 */
public class ConversationDescriptor extends ResourceDescriptor {
    public enum ViewState {
        UNSEEN, SEEN
    }

    private String agentName;
    private String userId;
    private URI agentResource;
    private ViewState viewState;
    private int conversationStepSize;
    private String createdByUserName;
    private Deployment.Environment environment;
    private ConversationState conversationState;

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public URI getAgentResource() {
        return agentResource;
    }

    public void setAgentResource(URI agentResource) {
        this.agentResource = agentResource;
    }

    /**
     * Reads {@code botName}, the name EDDI 5 stored {@link #agentName} under.
     *
     * <p>
     * {@code V6RenameMigration} renames the stored field; this keeps a descriptor
     * it has not reached readable, and makes a write of one carry the v6 name — a
     * descriptor is written by a whole-document replace, so a read that dropped the
     * v5 field used to drop it from the database too. Deliberately not a
     * {@code @JsonAlias}: with an alias, a document holding both names reads as
     * whichever comes last, and here the v6 name wins in either order. There is no
     * getter, so the v5 name is never written.
     * </p>
     */
    @JsonProperty("botName")
    private void setLegacyBotName(String botName) {
        if (agentName == null) {
            agentName = botName;
        }
    }

    /**
     * Reads {@code botResource}, the name EDDI 5 stored {@link #agentResource}
     * under — see {@link #setLegacyBotName}. Without it every conversation created
     * on EDDI 5 read with no agent, and was missing from every per-agent listing.
     */
    @JsonProperty("botResource")
    private void setLegacyBotResource(URI botResource) {
        if (agentResource == null) {
            agentResource = botResource;
        }
    }

    public ViewState getViewState() {
        return viewState;
    }

    public void setViewState(ViewState viewState) {
        this.viewState = viewState;
    }

    public int getConversationStepSize() {
        return conversationStepSize;
    }

    public void setConversationStepSize(int conversationStepSize) {
        this.conversationStepSize = conversationStepSize;
    }

    public String getCreatedByUserName() {
        return createdByUserName;
    }

    public void setCreatedByUserName(String createdByUserName) {
        this.createdByUserName = createdByUserName;
    }

    public Deployment.Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Deployment.Environment environment) {
        this.environment = environment;
    }

    public ConversationState getConversationState() {
        return conversationState;
    }

    public void setConversationState(ConversationState conversationState) {
        this.conversationState = conversationState;
    }
}
