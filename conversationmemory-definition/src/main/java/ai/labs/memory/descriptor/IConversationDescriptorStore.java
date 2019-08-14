package ai.labs.memory.descriptor;


import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.persistence.IDescriptorStore;

/**
 * @author ginccc
 */
public interface IConversationDescriptorStore extends IDescriptorStore<ConversationDescriptor> {
    String resourceUri = "eddi://ai.labs.conversation/conversationstore/conversations/";


    void updateTimeStamp(String conversationId);
}
