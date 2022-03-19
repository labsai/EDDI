package ai.labs.eddi.engine.utilities;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.utilities.ResourceUtilities;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.utils.SecurityUtilities;
import org.apache.commons.lang3.RandomStringUtils;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.URI;
import java.security.Principal;

import static ai.labs.eddi.utils.RestUtilities.createURI;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class ConversationSetup implements IConversationSetup {
    private IConversationDescriptorStore conversationDescriptorStore;

    @Inject
    public ConversationSetup(IConversationDescriptorStore conversationDescriptorStore) {
        this.conversationDescriptorStore = conversationDescriptorStore;
    }

    @Override
    public void createConversationDescriptor(String botId, IBot latestBot, String conversationId, URI conversationUri)
            throws IResourceStore.ResourceStoreException {

        var botVersion = latestBot.getBotVersion();
        var botResourceUri = createURI(IRestBotStore.resourceURI, botId, IRestBotStore.versionQueryParam, botVersion);
        Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
        conversationDescriptorStore.createDescriptor(conversationId, 0,
                ResourceUtilities.createConversationDescriptor(conversationUri, botResourceUri));
    }

    @Override
    public String computeAnonymousUserIdIfEmpty(String userId, Context userIdContext) {
        return isNullOrEmpty(userId) ?
                (userIdContext != null && userIdContext.getValue() instanceof String ?
                        userIdContext.getValue().toString() :
                        "anonymous-" + RandomStringUtils.randomAlphanumeric(10))
                : userId;
    }
}
