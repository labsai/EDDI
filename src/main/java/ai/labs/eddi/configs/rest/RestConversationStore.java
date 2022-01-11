package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.ConversationStatus;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertSimpleConversationMemory;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

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
                    URI resourceUri = conversationDescriptor.getResource();
                    var resourceId = extractResourceId(resourceUri);

                    if (!isNullOrEmpty(conversationId) && resourceId.getId().equals(conversationId)) {
                        return Collections.singletonList(conversationDescriptor);
                    }

                    var botResourceId = extractResourceId(conversationDescriptor.getBotResource());

                    if (isNullOrEmpty(botResourceId)) {
                        log.warn(String.format("botResourceId was null, should have an uri! (resource=%s)", resourceUri));
                        continue;
                    }

                    if (!isNullOrEmpty(botId) && !botId.equals(botResourceId.getId())) {
                        continue;
                    }

                    if (!isNullOrEmpty(botVersion) && !botVersion.equals(botResourceId.getVersion())) {
                        continue;
                    }

                    try {
                        memorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(resourceId.getId());
                    } catch (IResourceStore.ResourceNotFoundException e) {
                        String message = "Resource referenced in descriptor does not exist (anymore) [%s]. ";
                        message += "Ignoring this resource.";
                        log.warn(String.format(message, resourceUri));
                        continue;
                    }


                    if (!isNullOrEmpty(conversationState)) {
                        if (!conversationState.equals(memorySnapshot.getConversationState())) {
                            continue;
                        }
                    }

                    if (!isNullOrEmpty(viewState)) {
                        if (!viewState.equals(conversationDescriptor.getViewState())) {
                            continue;
                        }
                    }

                    conversationDescriptor.setEnvironment(memorySnapshot.getEnvironment());
                    conversationDescriptor.setConversationStepSize(memorySnapshot.getConversationSteps().size());
                    URI createdBy = conversationDescriptor.getCreatedBy();
                    if (createdBy != null) {
                        User user = userStore.readUser(extractResourceId(createdBy).getId());
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
    public List<ConversationStatus> getActiveConversations(String botId, Integer botVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        checkNotNull(botId, "botId");
        checkNotNull(botVersion, "botVersion");

        List<ConversationMemorySnapshot> conversationMemorySnapshots;
        List<ConversationStatus> conversationStatuses = new LinkedList<>();

        conversationMemorySnapshots = conversationMemoryStore.loadActiveConversationMemorySnapshot(botId, botVersion);
        for (var snapshot : conversationMemorySnapshots) {
            ConversationStatus conversationStatus = new ConversationStatus();
            String conversationId = snapshot.getId();
            conversationStatus.setConversationId(conversationId);
            conversationStatus.setBotId(botId);
            conversationStatus.setBotVersion(botVersion);
            conversationStatus.setConversationState(snapshot.getConversationState());
            var conversationDescriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
            conversationStatus.setLastInteraction(conversationDescriptor.getLastModifiedOn());
            conversationStatuses.add(conversationStatus);
        }

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
