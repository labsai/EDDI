package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.model.Context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.UUID;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createConversationDescriptorDocument;
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
        public void createConversationDescriptor(String agentId, IAgent latestAgent, String userId,
                        String conversationId, URI conversationUri)
                        throws ResourceStoreException, ResourceNotFoundException {

                var agentVersion = latestAgent.getAgentVersion();
                var agentResourceUri = createURI(IRestAgentStore.resourceURI, agentId,
                                IRestAgentStore.versionQueryParam, agentVersion);
                var conversationDescriptor = createConversationDescriptorDocument(conversationUri, agentResourceUri,
                                userId);
                var agentDescriptor = documentDescriptorStore.readDescriptor(latestAgent.getAgentId(),
                                latestAgent.getAgentVersion());

                conversationDescriptor.setAgentName(agentDescriptor.getName());
                conversationDescriptorStore.createDescriptor(conversationId, 0, conversationDescriptor);
        }

        @Override
        public String computeAnonymousUserIdIfEmpty(String userId, Context userIdContext) {
                return isNullOrEmpty(userId)
                                ? (userIdContext != null && userIdContext.getValue() instanceof String
                                                ? userIdContext.getValue().toString()
                                                : "anonymous-" + UUID.randomUUID().toString().replace("-", ""))
                                : userId;
        }
}
