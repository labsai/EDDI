/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Raises user events: delivers each to this node's streams and, in cluster
 * mode, tells the other nodes so a user whose tab is connected elsewhere gets
 * it too.
 *
 * <h3>Cross-pod delivery</h3> Through the cluster event bus that already
 * carries cache invalidations ({@link IClusterEventBus}, JetStream with
 * {@code eddi.messaging.type=nats}), as {@link ClusterEvent#USER_EVENT}
 * carrying the event and its {@link UserEventAudience}. The publishing node
 * delivers to its own streams synchronously and ignores its own event when it
 * comes back, as with every other cluster event. Each receiving node scopes the
 * event against its own connections — the audience is data, the decision is the
 * receiver's. Single-node ({@code in-memory}), the bus is a no-op and only the
 * local delivery happens.
 *
 * <h3>Never in the way</h3> Every method is best effort: it never throws and
 * never blocks on a slow client. Whatever raised the event — a pause, a fire, a
 * share — has already happened and is durable; the event only says so.
 */
@ApplicationScoped
public class UserEventPublisher {

    private static final Logger LOGGER = Logger.getLogger(UserEventPublisher.class);

    private final UserEventHub hub;
    private final IClusterEventBus clusterEvents;
    private final Instance<IConversationDescriptorStore> descriptorStore;

    @Inject
    public UserEventPublisher(UserEventHub hub, IClusterEventBus clusterEvents, Instance<IConversationDescriptorStore> descriptorStore) {
        this.hub = hub;
        this.clusterEvents = clusterEvents;
        this.descriptorStore = descriptorStore;
    }

    void onStart(@Observes StartupEvent startup) {
        if (hub.isEnabled()) {
            clusterEvents.subscribe(ClusterEvent.USER_EVENT, this::receive);
        }
    }

    /** Delivers here and, in cluster mode, on every other node. */
    public void publish(UserEvent event, UserEventAudience audience) {
        if (event == null || audience == null || audience.isEmpty() || !hub.isEnabled()) {
            return;
        }
        try {
            hub.dispatch(event, audience);
            if (clusterEvents.isClustered()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("event", event.toMap());
                payload.put("audience", audience.toMap());
                clusterEvents.publish(ClusterEvent.USER_EVENT, payload);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not raise user event %s: %s", event.kind(), e.getMessage());
        }
    }

    /** An event another node raised. */
    void receive(ClusterEvent clusterEvent) {
        Object event = clusterEvent.get("event");
        Object audience = clusterEvent.get("audience");
        UserEvent parsed = event instanceof Map<?, ?> map ? UserEvent.fromMap(map) : null;
        UserEventAudience scope = audience instanceof Map<?, ?> map ? UserEventAudience.fromMap(map) : null;
        if (parsed == null || scope == null) {
            LOGGER.debugf("Ignoring an unreadable user event from node %s", sanitize(clusterEvent.originNode()));
            return;
        }
        hub.dispatch(parsed, scope);
    }

    /**
     * The owner and agent of a 1:1 conversation, from its descriptor — the record
     * every ownership check reads.
     *
     * @return {@code null} when it cannot be read; the caller then raises nothing
     */
    public ConversationOwner conversationOwner(String conversationId) {
        if (conversationId == null || descriptorStore == null || !descriptorStore.isResolvable()) {
            return null;
        }
        try {
            ConversationDescriptor descriptor = descriptorStore.get().readDescriptor(conversationId, 0);
            if (descriptor == null) {
                return null;
            }
            String agentId = null;
            if (descriptor.getAgentResource() != null) {
                var resourceId = RestUtilities.extractResourceId(descriptor.getAgentResource());
                agentId = resourceId != null ? resourceId.getId() : null;
            }
            return new ConversationOwner(descriptor.getUserId(), agentId);
        } catch (Exception e) {
            LOGGER.debugf("No owner for user event on conversation %s: %s", sanitize(conversationId), e.getMessage());
            return null;
        }
    }

    /**
     * Who owns a conversation, and which agent it talks to.
     *
     * @param userId
     *            the owner; null or blank for a legacy, ownerless conversation
     * @param agentId
     *            the agent's id, or null
     */
    public record ConversationOwner(String userId, String agentId) {
    }
}
