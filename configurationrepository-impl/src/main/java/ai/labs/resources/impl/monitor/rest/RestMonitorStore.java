package ai.labs.resources.impl.monitor.rest;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationState;
import ai.labs.memory.rest.IRestMonitorStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestMonitorStore implements IRestMonitorStore {
    private final IUserStore userStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;

    @Inject
    public RestMonitorStore(IUserStore userStore,
                            IDocumentDescriptorStore documentDescriptorStore,
                            IConversationDescriptorStore conversationDescriptorStore,
                            IConversationMemoryStore conversationMemoryStore) {
        this.userStore = userStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    @Override
    public List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String botId, Integer botVersion, ConversationState conversationState, ConversationDescriptor.ViewState viewState) {
        try {
            List<ConversationDescriptor> conversationDescriptors;
            List<ConversationDescriptor> retConversationDescriptors = new LinkedList<ConversationDescriptor>();
            do {
                conversationDescriptors = conversationDescriptorStore.readDescriptors("ai.labs.conversation", null, index, limit, false);
                for (ConversationDescriptor conversationDescriptor : conversationDescriptors) {
                    ConversationMemorySnapshot memorySnapshot = readConversationLog(RestUtilities.extractResourceId(conversationDescriptor.getResource()).getId());
                    if (!RuntimeUtilities.isNullOrEmpty(botId)) {
                        if (!botId.equals(memorySnapshot.getBotId())) {
                            continue;
                        }
                    }

                    if (!RuntimeUtilities.isNullOrEmpty(botVersion)) {
                        if (!botVersion.equals(memorySnapshot.getBotVersion())) {
                            continue;
                        }
                    }

                    if (!RuntimeUtilities.isNullOrEmpty(conversationState)) {
                        if (!conversationState.equals(memorySnapshot.getConversationState())) {
                            continue;
                        }
                    }

                    if (!RuntimeUtilities.isNullOrEmpty(viewState)) {
                        if (!viewState.equals(conversationDescriptor.getViewState())) {
                            continue;
                        }
                    }

                    conversationDescriptor.setEnvironment(memorySnapshot.getEnvironment());
                    conversationDescriptor.setConversationStepSize(memorySnapshot.getConversationSteps().size());
                    URI createdBy = conversationDescriptor.getCreatedBy();
                    if (createdBy != null) {
                        User user = userStore.readUser(RestUtilities.extractResourceId(createdBy).getId());
                        conversationDescriptor.setCreatedByUserName(user.getDisplayName());
                    }
                    conversationDescriptor.setBotName(documentDescriptorStore.readDescriptor(memorySnapshot.getBotId(), memorySnapshot.getBotVersion()).getName());
                    conversationDescriptor.setConversationState(memorySnapshot.getConversationState());

                    retConversationDescriptors.add(conversationDescriptor);
                }
                index++;
            } while (!conversationDescriptors.isEmpty() && retConversationDescriptors.size() < limit);

            return retConversationDescriptors;

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(e);
        }
    }

    @Override
    public ConversationMemorySnapshot readConversationLog(String conversationId) {
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        try {
            return conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(e);
        }
    }

    @Override
    public void deleteConversationLog(String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        //no real deletion here, DocumentDescriptorInterceptor will mark it as deleted
    }
}
