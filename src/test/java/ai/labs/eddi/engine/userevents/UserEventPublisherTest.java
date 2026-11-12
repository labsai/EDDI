/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserEventPublisher")
class UserEventPublisherTest {

    /**
     * Two nodes joined by a fake cluster bus: what one publishes, the other's
     * subscribed handler receives — the JetStream bus's contract (a node never
     * receives its own event back).
     */
    private static final class FakeBus implements IClusterEventBus {
        final List<Consumer<ClusterEvent>> handlers = new ArrayList<>();
        FakeBus peer;
        final List<Map<String, Object>> published = new ArrayList<>();

        @Override
        public void publish(String type, Map<String, Object> payload) {
            published.add(payload);
            // Round-trip through JSON, as JetStream does.
            Map<String, Object> wire;
            try {
                var json = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> copy = json.readValue(json.writeValueAsBytes(payload), Map.class);
                wire = copy;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            var event = new ClusterEvent(1, "e-1", type, "node-a", "boot-a", 0, null, wire);
            peer.handlers.forEach(h -> h.accept(event));
        }

        @Override
        public void subscribe(String type, Consumer<ClusterEvent> handler) {
            if (ClusterEvent.USER_EVENT.equals(type)) {
                handlers.add(handler);
            }
        }

        @Override
        public void onResync(Runnable flush) {
        }

        @Override
        public boolean isClustered() {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static UserEventHub hub() {
        Instance<ResourceAccessGuard> guard = mock(Instance.class);
        return new UserEventHub(true, 8, 100, 64, 100, Duration.ofSeconds(20), Duration.ofHours(1), new OwnershipValidator(true), guard,
                new SimpleMeterRegistry(), System::currentTimeMillis, false);
    }

    @SuppressWarnings("unchecked")
    private static Instance<IConversationDescriptorStore> noStore() {
        Instance<IConversationDescriptorStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static UserEventHub.Subscriber user(String principal) {
        return new UserEventHub.Subscriber(principal, false, false, List.of(), 0);
    }

    @Test
    @DisplayName("an event raised on node A reaches its user connected to node B, and nobody else there")
    void crossPodDelivery() throws Exception {
        var busA = new FakeBus();
        var busB = new FakeBus();
        busA.peer = busB;
        busB.peer = busA;
        var hubA = hub();
        var hubB = hub();
        var publisherA = new UserEventPublisher(hubA, busA, noStore());
        var publisherB = new UserEventPublisher(hubB, busB, noStore());
        publisherA.onStart(null);
        publisherB.onStart(null);

        var aliceOnB = new RecordingSink();
        var bobOnB = new RecordingSink();
        hubB.open(user("alice"), aliceOnB);
        hubB.open(user("bob"), bobOnB);

        var event = UserEvent.of(UserEvent.KIND_JOB_FINISHED).withConversation("conv-1", "agent-1").withJob(UserEvent.JOB_CONTINUATION, null);
        publisherA.publish(event, UserEventAudience.principal("alice"));
        // hubB.dispatch is asynchronous; deliver what it queued.
        awaitFrames(aliceOnB, 1);

        assertEquals(1, aliceOnB.userEvents().size());
        assertEquals(UserEvent.KIND_JOB_FINISHED, aliceOnB.userEvents().getFirst().name());
        assertTrue(aliceOnB.userEvents().getFirst().data().contains(event.id()), "the same event, by id");
        Thread.sleep(50);
        assertEquals(0, bobOnB.userEvents().size(), "the other user on node B must not get it");
    }

    @Test
    @DisplayName("the cluster payload names principals only as hashes")
    void principalsHashedOnTheWire() {
        var busA = new FakeBus();
        busA.peer = new FakeBus();
        var publisher = new UserEventPublisher(hub(), busA, noStore());

        publisher.publish(UserEvent.of(UserEvent.KIND_APPROVAL_DECIDED).withConversation("conv-1", "agent-1"),
                UserEventAudience.principal("gregor@example.com").andApprovers());

        String wire = busA.published.getFirst().toString();
        assertFalse(wire.contains("gregor@example.com"), "a raw principal must not reach the event stream: " + wire);
        assertTrue(wire.contains("conv-1"));
    }

    @Test
    @DisplayName("single node: delivered locally, nothing published")
    void singleNode() {
        var bus = mock(IClusterEventBus.class);
        when(bus.isClustered()).thenReturn(false);
        var hub = mock(UserEventHub.class);
        when(hub.isEnabled()).thenReturn(true);
        var publisher = new UserEventPublisher(hub, bus, noStore());

        var event = UserEvent.of(UserEvent.KIND_TEAM_STARTED);
        var audience = UserEventAudience.principal("alice");
        publisher.publish(event, audience);

        verify(hub).dispatch(event, audience);
        verify(bus, never()).publish(anyString(), anyMap());
    }

    @Test
    @DisplayName("an audience nobody can be in raises nothing")
    void emptyAudience() {
        var bus = mock(IClusterEventBus.class);
        var hub = mock(UserEventHub.class);
        when(hub.isEnabled()).thenReturn(true);
        var publisher = new UserEventPublisher(hub, bus, noStore());

        publisher.publish(UserEvent.of(UserEvent.KIND_TEAM_STARTED), UserEventAudience.principal("  "));

        verify(hub, never()).dispatch(any(), any());
        verify(bus, never()).publish(anyString(), anyMap());
    }

    @Test
    @DisplayName("an event of a kind this node does not know is dropped, not forwarded")
    void unknownKindDropped() {
        var hub = mock(UserEventHub.class);
        var publisher = new UserEventPublisher(hub, mock(IClusterEventBus.class), noStore());

        publisher.receive(new ClusterEvent(1, "e", ClusterEvent.USER_EVENT, "n", "b", 0, null,
                Map.of("event", Map.of("id", "x", "kind", "chat.message"), "audience", Map.of("principals", List.of("h")))));

        verify(hub, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("the audience survives the wire: hashes, approvers and the USE resource")
    void audienceRoundTrip() {
        var hub = mock(UserEventHub.class);
        var publisher = new UserEventPublisher(hub, mock(IClusterEventBus.class), noStore());
        var audience = UserEventAudience.principal("alice").andApprovers().requiringUse("agent-1");

        publisher.receive(new ClusterEvent(1, "e", ClusterEvent.USER_EVENT, "n", "b", 0, null,
                Map.of("event", UserEvent.of(UserEvent.KIND_APPROVAL_PENDING).toMap(), "audience", audience.toMap())));

        var captor = ArgumentCaptor.forClass(UserEventAudience.class);
        verify(hub).dispatch(any(), captor.capture());
        assertEquals(audience, captor.getValue());
    }

    @Test
    @DisplayName("the owner of a conversation comes from its descriptor")
    @SuppressWarnings("unchecked")
    void ownerFromDescriptor() throws Exception {
        var store = mock(IConversationDescriptorStore.class);
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId("alice");
        descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/0123456789abcdef01234567?version=2"));
        when(store.readDescriptor(eq("conv-1"), any())).thenReturn(descriptor);
        Instance<IConversationDescriptorStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(store);
        var publisher = new UserEventPublisher(mock(UserEventHub.class), mock(IClusterEventBus.class), instance);

        var owner = publisher.conversationOwner("conv-1");

        assertEquals("alice", owner.userId());
        assertEquals("0123456789abcdef01234567", owner.agentId());
        when(store.readDescriptor(eq("gone"), any())).thenThrow(new RuntimeException("not found"));
        assertNull(publisher.conversationOwner("gone"));
    }

    private static void awaitFrames(RecordingSink sink, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (sink.userEvents().size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
