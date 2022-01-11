package ai.labs.eddi.engine.memory.descriptor;


import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;

/**
 * @author ginccc
 */
public interface IConversationDescriptorStore extends IDescriptorStore<ConversationDescriptor> {
    String resourceUri = "eddi://ai.labs.conversation/conversationstore/conversations/";


    void updateTimeStamp(String conversationId);
}
