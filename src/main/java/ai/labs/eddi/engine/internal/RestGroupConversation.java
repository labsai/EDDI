package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for group conversation operations.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestGroupConversation implements IRestGroupConversation {

    private static final Logger LOGGER = Logger.getLogger(RestGroupConversation.class);

    private final IGroupConversationService groupConversationService;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public RestGroupConversation(IGroupConversationService groupConversationService, IJsonSerialization jsonSerialization) {
        this.groupConversationService = groupConversationService;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public Response discuss(String groupId, DiscussRequest request) {
        try {
            String userId = request.userId() != null ? request.userId() : "anonymous";
            GroupConversation gc = groupConversationService.discuss(groupId, request.question(), userId, 0);
            URI location = URI.create("/groups/" + groupId + "/conversations/" + gc.getId());
            return Response.created(location).entity(gc).build();
        } catch (IGroupConversationService.GroupDepthExceededException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.errorf("Group discussion failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public void discussStreaming(String groupId, DiscussRequest request, SseEventSink eventSink, Sse sse) {
        try {
            String userId = request.userId() != null ? request.userId() : "anonymous";

            GroupDiscussionEventListener listener = new GroupDiscussionEventListener() {
                @Override
                public void onGroupStart(GroupConversationEventSink.GroupStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_START, toJson(event));
                }

                @Override
                public void onPhaseStart(GroupConversationEventSink.PhaseStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_PHASE_START, toJson(event));
                }

                @Override
                public void onSpeakerStart(GroupConversationEventSink.SpeakerStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SPEAKER_START, toJson(event));
                }

                @Override
                public void onSpeakerComplete(GroupConversationEventSink.SpeakerCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SPEAKER_COMPLETE, toJson(event));
                }

                @Override
                public void onPhaseComplete(GroupConversationEventSink.PhaseCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_PHASE_COMPLETE, toJson(event));
                }

                @Override
                public void onSynthesisStart(GroupConversationEventSink.SynthesisStartEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SYNTHESIS_START, toJson(event));
                }

                @Override
                public void onSynthesisComplete(GroupConversationEventSink.SynthesisCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_SYNTHESIS_COMPLETE, toJson(event));
                }

                @Override
                public void onGroupComplete(GroupConversationEventSink.GroupCompleteEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_COMPLETE, toJson(event));
                    closeQuietly(eventSink);
                }

                @Override
                public void onGroupError(GroupConversationEventSink.GroupErrorEvent event) {
                    sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR, toJson(event));
                    closeQuietly(eventSink);
                }
            };

            groupConversationService.startAndDiscussAsync(groupId, request.question(), userId, listener);

        } catch (IResourceStore.ResourceNotFoundException e) {
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            closeQuietly(eventSink);
        } catch (Exception e) {
            LOGGER.errorf("Group streaming discussion failed: %s", e.getMessage());
            sendEvent(eventSink, sse, GroupConversationEventSink.EVENT_GROUP_ERROR, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            closeQuietly(eventSink);
        }
    }

    @Override
    public GroupConversation readGroupConversation(String groupId, String groupConversationId) {
        try {
            return groupConversationService.readGroupConversation(groupConversationId);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteGroupConversation(String groupId, String groupConversationId) {
        try {
            groupConversationService.deleteGroupConversation(groupConversationId);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, Integer index, Integer limit) {
        try {
            return groupConversationService.listGroupConversations(groupId, index, limit);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    // --- SSE Helpers ---

    private void sendEvent(SseEventSink eventSink, Sse sse, String eventName, String data) {
        if (eventSink.isClosed()) {
            LOGGER.debugf("SSE sink closed, dropping event: %s", eventName);
            return;
        }
        try {
            eventSink.send(sse.newEventBuilder().name(eventName).data(String.class, data).build());
        } catch (Exception e) {
            LOGGER.warnf("Failed to send SSE event '%s': %s", eventName, e.getMessage());
        }
    }

    private void closeQuietly(SseEventSink eventSink) {
        try {
            if (!eventSink.isClosed()) {
                eventSink.close();
            }
        } catch (Exception e) {
            LOGGER.debugf("Error closing SSE sink: %s", e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return jsonSerialization.serialize(obj);
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize SSE event: %s", e.getMessage());
            return "{}";
        }
    }

    private String escapeJson(String text) {
        if (text == null)
            return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
