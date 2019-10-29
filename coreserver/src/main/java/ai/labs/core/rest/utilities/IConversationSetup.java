package ai.labs.core.rest.utilities;

import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.IBot;

import java.net.URI;

public interface IConversationSetup {
    URI createConversationDescriptor(String botId, IBot latestBot, String conversationId, URI conversationUri)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void createPermissions(String conversationId, URI userURI) throws IResourceStore.ResourceStoreException;

    String computeAnonymousUserIdIfEmpty(String userId);
}
