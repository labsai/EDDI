package ai.labs.resources.impl.monitor.rest;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.memory.rest.IRestConversationStore;
import ai.labs.models.ConversationState;
import ai.labs.models.ConversationStatus;
import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.model.ResourceId;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.URIUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.memory.ConversationMemoryUtilities.convertSimpleConversationMemory;
import static ai.labs.utilities.RuntimeUtilities.checkNotNull;

/**
 * @author ginccc
 */
@Slf4j
public class RestConversationStore implements IRestConversationStore {
    private final IUserStore userStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;

    @Inject
    public RestConversationStore(IUserStore userStore,
                                 IDocumentDescriptorStore documentDescriptorStore,
                                 IConversationDescriptorStore conversationDescriptorStore,
                                 IConversationMemoryStore conversationMemoryStore) {
        this.userStore = userStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    @Override
    public List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit,
                                                                    String conversationId, String botId, Integer botVersion,
                                                                    ConversationState conversationState, ConversationDescriptor.ViewState viewState) {
        try {
            List<ConversationDescriptor> conversationDescriptors;
            List<ConversationDescriptor> retConversationDescriptors = new LinkedList<>();
            do {
                conversationDescriptors = conversationDescriptorStore.
                        readDescriptors("ai.labs.conversation", null, index, limit, false);

                ConversationMemorySnapshot memorySnapshot;
                for (ConversationDescriptor conversationDescriptor : conversationDescriptors) {
                    IResourceStore.IResourceId resourceId =
                            RestUtilities.extractResourceId(conversationDescriptor.getResource());
                    try {
                        memorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(resourceId.getId());
                    } catch (IResourceStore.ResourceNotFoundException e) {
                        String message = "Resource referenced in descriptor does not exist (anymore) [%s]. ";
                        message += "Ignoring this resource.";
                        log.warn(String.format(message, conversationDescriptor.getResource()));
                        continue;
                    }

                    if (!RuntimeUtilities.isNullOrEmpty(conversationId)) {
                        if (!conversationId.equals(memorySnapshot.getConversationId())) {
                            continue;
                        }
                    }

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
    public ConversationMemorySnapshot readRawConversationLog(String conversationId) {
        checkNotNull(conversationId, "conversationId");

        try {
            return conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(String.format("Conversation (%s) could not be found", conversationId));
        }
    }

    @Override
    public SimpleConversationMemorySnapshot readSimpleConversationLog(String conversationId,
                                                                      Boolean returnDetailed,
                                                                      Boolean returnCurrentStepOnly,
                                                                      List<String> returningFields) {
        checkNotNull(conversationId, "conversationId");
        checkNotNull(returnDetailed, "returnDetailed");
        checkNotNull(returnCurrentStepOnly, "returnCurrentStepOnly");

        try {
            return convertSimpleConversationMemory(conversationMemoryStore.loadConversationMemorySnapshot(conversationId), returnDetailed);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(String.format("Conversation (%s) could not be found", conversationId));
        }
    }

    @Override
    public void deleteConversationLog(String conversationId, Boolean deletePermanently)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");

        if (deletePermanently) {
            conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
            log.info(String.format("Conversation has been permanently deleted (conversationId=%s)", conversationId));
        }

        // DocumentDescriptorInterceptor will mark the DocumentDescriptor of this resource as deleted,
        // regardless of whether it has been permanently deleted or not
    }

    @Override
    public List<ConversationStatus> getActiveConversations(String botId,
                                                           Integer botVersion,
                                                           Integer index,
                                                           Integer limit) {
        checkNotNull(botId, "botId");
        checkNotNull(botVersion, "botVersion");

        List<ConversationDescriptor> conversationDescriptors;
        List<ConversationStatus> conversationStatuses = new LinkedList<>();
        do {
            conversationDescriptors = readConversationDescriptors(index, limit, null, botId, botVersion,
                    null, null);

            conversationStatuses.addAll(conversationDescriptors.stream().
                    filter(conversationDescriptor ->
                            conversationDescriptor.getConversationState() != ConversationState.ENDED).
                    map(conversationDescriptor -> {
                        ConversationStatus conversationStatus = new ConversationStatus();
                        URI resourceUri = conversationDescriptor.getResource();
                        ResourceId resourceId = URIUtilities.extractResourceId(resourceUri);
                        conversationStatus.setConversationId(resourceId.getId());
                        conversationStatus.setBotId(botId);
                        conversationStatus.setBotVersion(botVersion);
                        conversationStatus.setConversationState(conversationDescriptor.getConversationState());
                        conversationStatus.setLastInteraction(conversationDescriptor.getLastModifiedOn());
                        return conversationStatus;
                    }).collect(Collectors.toList()));
            index++;
        } while (!conversationDescriptors.isEmpty() && conversationStatuses.size() < limit);

        return conversationStatuses;
    }

    @Override
    public Response endActiveConversations(List<ConversationStatus> conversationStatuses) {
        try {
            for (ConversationStatus conversationStatus : conversationStatuses) {
                String conversationId = conversationStatus.getConversationId();
                conversationMemoryStore.setConversationState(
                        conversationId,
                        ConversationState.ENDED);

                ConversationDescriptor conversationDescriptor = conversationDescriptorStore.
                        readDescriptor(conversationId, 0);
                conversationDescriptor.setConversationState(ConversationState.ENDED);
                conversationDescriptorStore.setDescriptor(conversationId, 0, conversationDescriptor);

                log.info(String.format("conversation (%s) has been set to ENDED", conversationId));
            }

            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }
}
