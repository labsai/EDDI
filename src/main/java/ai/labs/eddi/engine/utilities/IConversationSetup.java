package ai.labs.eddi.engine.utilities;


import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.model.Context;

import java.net.URI;

public interface IConversationSetup {
    void createConversationDescriptor(String botId, IBot latestBot, String userId, String conversationId, URI conversationUri)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    String computeAnonymousUserIdIfEmpty(String userId, Context userIdContext);
}
