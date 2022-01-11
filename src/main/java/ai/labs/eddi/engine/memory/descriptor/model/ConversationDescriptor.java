package ai.labs.eddi.engine.memory.descriptor.model;

import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.Deployment;
import ai.labs.eddi.models.ResourceDescriptor;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;

/**
 * @author ginccc
 */
@Getter
@Setter
public class ConversationDescriptor extends ResourceDescriptor {
    public enum ViewState {
        UNSEEN,
        SEEN
    }

    private String botName;
    private URI botResource;
    private ViewState viewState;
    private int conversationStepSize;
    private String createdByUserName;
    private Deployment.Environment environment;
    private ConversationState conversationState;
}

