package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.ConversationState;
import ai.labs.eddi.engine.model.ConversationStatus;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.ThreadContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertSimpleConversationMemory;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestConversationStore implements IRestConversationStore {
    public static final String DESCRIPTOR_TYPE = "ai.labs.conversation";

    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IRuntime runtime;
    private final Integer deleteEndedConversationsOnceOlderThanDays;

    private static final Logger log = Logger.getLogger(RestConversationStore.class);

    @Inject
    public RestConversationStore(IDocumentDescriptorStore documentDescriptorStore,
                                 IConversationDescriptorStore conversationDescriptorStore,
                                 IConversationMemoryStore conversationMemoryStore,
                                 IRuntime runtime,
                                 @ConfigProperty(name = "eddi.conversations.deleteEndedConversationsOnceOlderThanDays")
                                 Integer deleteEndedConversationsOnceOlderThanDays) {

        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.runtime = runtime;
        this.deleteEndedConversationsOnceOlderThanDays = deleteEndedConversationsOnceOlderThanDays;
    }

    @Override
    public List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter,
                                                                    String conversationId, String botId, Integer botVersion,
                                                                    ConversationState conversationState,
                                                                    ConversationDescriptor.ViewState viewState) {
        try {
            List<ConversationDescriptor> conversationDescriptors;
            List<ConversationDescriptor> retConversationDescriptors = new LinkedList<>();

            do {
                conversationDescriptors = readConversationDescriptors(index, limit, filter);
                if (conversationDescriptors.isEmpty() && index == 0 && !isNullOrEmpty(filter)) {
                    conversationDescriptors = readConversationDescriptors(index, limit, null);
                }

                for (var conversationDescriptor : conversationDescriptors) {
                    URI resourceUri = conversationDescriptor.getResource();
                    var botResourceId = extractResourceId(resourceUri);
                    if (botResourceId == null) {
                        log.warn(format("botResourceId was null, this should never happen. (%s)", resourceUri));
                        continue;
                    }

                    populateDataToDescriptor(conversationDescriptor, botResourceId);

                    if (!isNullOrEmpty(botId) && !botId.equals(botResourceId.getId())) {
                        continue;
                    }

                    if (!isNullOrEmpty(botVersion) && !botVersion.equals(botResourceId.getVersion())) {
                        continue;
                    }

                    if (!isNullOrEmpty(conversationState)) {
                        if (!conversationState.equals(conversationDescriptor.getConversationState())) {
                            continue;
                        }
                    }

                    if (!isNullOrEmpty(viewState)) {
                        if (!viewState.equals(conversationDescriptor.getViewState())) {
                            continue;
                        }
                    }

                    retConversationDescriptors.add(conversationDescriptor);
                }

                index++;
            } while (!conversationDescriptors.isEmpty() && retConversationDescriptors.size() < limit);

            return retConversationDescriptors;

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException();
        }
    }

    private List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        return conversationDescriptorStore.readDescriptors(DESCRIPTOR_TYPE, filter, index, limit, false);
    }

    private void populateDataToDescriptor(ConversationDescriptor conversationDescriptor,
                                          IResourceStore.IResourceId resourceId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        try {
            var memorySnapshot =
                    conversationMemoryStore.loadConversationMemorySnapshot(resourceId.getId());

            if (conversationDescriptor.getUserId() == null) {
                //fallback for older conversations pre v5.1.6
                conversationDescriptor.setUserId(memorySnapshot.getUserId());
            }
            conversationDescriptor.setEnvironment(memorySnapshot.getEnvironment());
            conversationDescriptor.setConversationStepSize(memorySnapshot.getConversationSteps().size());
            conversationDescriptor.setConversationState(memorySnapshot.getConversationState());
            if (isNullOrEmpty(conversationDescriptor.getBotName())) {
                var documentDescriptor =
                        documentDescriptorStore.readDescriptor(memorySnapshot.getBotId(), memorySnapshot.getBotVersion());

                conversationDescriptor.setBotName(documentDescriptor.getName());
            }

        } catch (IResourceStore.ResourceNotFoundException e) {
            String message = "Resource referenced in descriptor does not exist (anymore) [%s, %s]. ";
            message += "Ignoring this resource.";
            log.warn(format(message, resourceId.getId(), resourceId.getVersion()));
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
            throw new NotFoundException(format("Conversation (%s) could not be found", conversationId));
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
            return convertSimpleConversationMemory(
                    conversationMemoryStore.loadConversationMemorySnapshot(conversationId),
                    returnDetailed, returnCurrentStepOnly);

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(format("Conversation (%s) could not be found", conversationId));
        }
    }

    @Override
    public void deleteConversationLog(String conversationId, Boolean deletePermanently)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");

        if (deletePermanently) {
            conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
            log.info(format("Conversation has been permanently deleted (conversationId=%s)", conversationId));
        }

        // DocumentDescriptorInterceptor will mark the DocumentDescriptor of this resource as deleted,
        // regardless of whether it has been permanently deleted or not
    }

    @Scheduled(every = "24h")
    public void deleteEndedConversationsOlderThanXDays() {
        runtime.submitCallable(() -> {
            try {
                var amountOfEndedConversations =
                        permanentlyDeleteEndedConversationLogs(deleteEndedConversationsOnceOlderThanDays);

                if (amountOfEndedConversations > 0) {
                    log.info(format("Successfully deleted %s conversations, which were older than %s days",
                            amountOfEndedConversations, deleteEndedConversationsOnceOlderThanDays));
                }
            } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
                log.error(e.getLocalizedMessage(), e);
            }
            return null;
        }, ThreadContext.getResources());
    }

    @Override
    public Integer permanentlyDeleteEndedConversationLogs(Integer deleteOlderThanDays)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        int amountOfEndedConversations = 0;
        if (deleteOlderThanDays != null && deleteOlderThanDays > -1) {
            var deleteOlderThanThisDate = Date.from(Instant.now().minus(Duration.ofDays(deleteOlderThanDays)));
            var endedConversationIds = conversationMemoryStore.getEndedConversationIds();

            for (var endedConversationId : endedConversationIds) {
                var descriptor = documentDescriptorStore.readDescriptor(endedConversationId, 0);
                if (descriptor.getLastModifiedOn().before(deleteOlderThanThisDate)) {
                    documentDescriptorStore.deleteAllDescriptor(endedConversationId);
                    conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                    amountOfEndedConversations++;
                }
            }
        }

        return amountOfEndedConversations;
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

                log.info(format("conversation (%s) has been set to ENDED", conversationId));
            }

            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }
}
