package ai.labs.eddi.engine.memory.descriptor.model;

import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.configs.descriptors.model.ResourceDescriptor;

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
