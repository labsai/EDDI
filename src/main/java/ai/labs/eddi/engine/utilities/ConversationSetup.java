package ai.labs.eddi.engine.utilities;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.models.Context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.UUID;

import static ai.labs.eddi.configs.utilities.ResourceUtilities.createConversationDescriptorDocument;
import static ai.labs.eddi.utils.RestUtilities.createURI;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class ConversationSetup implements IConversationSetup {
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public ConversationSetup(IConversationDescriptorStore conversationDescriptorStore,
                             IDocumentDescriptorStore documentDescriptorStore) {
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public void createConversationDescriptor(String botId, IBot latestBot, String conversationId, URI conversationUri)
            throws ResourceStoreException, ResourceNotFoundException {

        var botVersion = latestBot.getBotVersion();
        var botResourceUri =
                createURI(IRestBotStore.resourceURI, botId, IRestBotStore.versionQueryParam, botVersion);
        var conversationDescriptor =
                createConversationDescriptorDocument(conversationUri, botResourceUri);
        var botDescriptor =
                documentDescriptorStore.readDescriptor(latestBot.getBotId(), latestBot.getBotVersion());

        conversationDescriptor.setBotName(botDescriptor.getName());
        conversationDescriptorStore.createDescriptor(conversationId, 0, conversationDescriptor);
    }

    @Override
    public String computeAnonymousUserIdIfEmpty(String userId, Context userIdContext) {
        return isNullOrEmpty(userId) ?
                (userIdContext != null && userIdContext.getValue() instanceof String ?
                        userIdContext.getValue().toString() :
                        "anonymous-" + UUID.randomUUID().toString().replace("-", ""))
                : userId;
    }
}
