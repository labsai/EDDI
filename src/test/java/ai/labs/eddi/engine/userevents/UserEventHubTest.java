/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UserEventHub")
class UserEventHubTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SimpleMeterRegistry meters;
    private ResourceAccessGuard guard;
    private Instance<ResourceAccessGuard> guardInstance;
    private AtomicLong now;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meters = new SimpleMeterRegistry();
        guard = mock(ResourceAccessGuard.class);
        guardInstance = mock(Instance.class);
        when(guardInstance.isResolvable()).thenReturn(true);
        when(guardInstance.get()).thenReturn(guard);
        now = new AtomicLong(1_000_000L);
    }

    private UserEventHub hub(boolean authEnabled, int perUser, int perNode, int buffer) {
        return new UserEventHub(true, perUser, perNode, buffer, 100, Duration.ofSeconds(20), Duration.ofHours(1),
                new OwnershipValidator(authEnabled), guardInstance, meters, now::get, false);
    }

    private UserEventHub hub() {
        return hub(true, 8, 100, 64);
    }

    private static UserEventHub.Subscriber user(String principal) {
        return new UserEventHub.Subscriber(principal, false, false, List.of(), 0);
    }

    private static UserEventHub.Subscriber approver(String principal) {
        return new UserEventHub.Subscriber(principal, false, true, List.of(), 0);
    }

    private static UserEventHub.Subscriber admin(String principal) {
        return new UserEventHub.Subscriber(principal, true, false, List.of(), 0);
    }

    private static UserEvent pending() {
        return UserEvent.of(UserEvent.KIND_APPROVAL_PENDING).withSurface(UserEvent.SURFACE_REGULAR).withConversation("conv-1", "agent-1");
    }

    private double counter(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("scoping")
    class Scoping {

        @Test
        @DisplayName("a user never receives another user's event")
        void neverAnotherUsersEvent() throws Exception {
            var hub = hub();
            var alice = new RecordingSink();
            var bob = new RecordingSink();
            hub.open(user("alice"), alice);
            hub.open(user("bob"), bob);

            hub.fanOut(pending(), UserEventAudience.principal("alice"));

            assertEquals(1, alice.userEvents().size());
            assertEquals(0, bob.userEvents().size(), "bob must not see alice's approval");
        }

        @Test
        @DisplayName("a principal is matched exactly — a prefix or another case is somebody else")
        void exactPrincipalMatch() throws Exception {
            var hub = hub();
            var alicia = new RecordingSink();
            var upper = new RecordingSink();
            hub.open(user("alice2"), alicia);
            hub.open(user("ALICE"), upper);

            hub.fanOut(pending(), UserEventAudience.principal("alice"));

            assertEquals(0, alicia.userEvents().size());
            assertEquals(0, upper.userEvents().size());
        }

        @Test
        @DisplayName("an approvers audience reaches admins and approvers, not other users")
        void approversAudience() throws Exception {
            var hub = hub();
            var owner = new RecordingSink();
            var approverSink = new RecordingSink();
            var adminSink = new RecordingSink();
            var stranger = new RecordingSink();
            hub.open(user("owner"), owner);
            hub.open(approver("anna"), approverSink);
            hub.open(admin("root"), adminSink);
            hub.open(user("stranger"), stranger);

            hub.fanOut(pending(), UserEventAudience.principal("owner").andApprovers());

            assertEquals(1, owner.userEvents().size());
            assertEquals(1, approverSink.userEvents().size());
            assertEquals(1, adminSink.userEvents().size());
            assertEquals(0, stranger.userEvents().size());
        }

        @Test
        @DisplayName("an administrators audience reaches admins only, not approvers")
        void administratorsAudience() throws Exception {
            var hub = hub();
            var approverSink = new RecordingSink();
            var adminSink = new RecordingSink();
            hub.open(approver("anna"), approverSink);
            hub.open(admin("root"), adminSink);

            hub.fanOut(UserEvent.of(UserEvent.KIND_NOTIFICATION_CREATED).withNotification("n-1", "UPDATE_AVAILABLE"),
                    UserEventAudience.administrators());

            assertEquals(0, approverSink.userEvents().size());
            assertEquals(1, adminSink.userEvents().size());
        }

        @Test
        @DisplayName("an owner who may no longer USE the agent is not told of its pause; an admin is, without a check")
        void useCheckGatesTheOwner() throws Exception {
            var hub = hub();
            var owner = new RecordingSink();
            var adminSink = new RecordingSink();
            hub.open(new UserEventHub.Subscriber("owner", false, false, List.of("team:t1"), 0), owner);
            hub.open(admin("owner-admin"), adminSink);
            when(guard.checkPrincipalUse(eq("agent-1"), eq("owner"), eq(List.of("team:t1")), eq(false)))
                    .thenReturn(ResourceAccessGuard.UseCheck.DENIED);

            hub.fanOut(pending(), UserEventAudience.principals(List.of("owner", "owner-admin")).requiringUse("agent-1"));

            assertEquals(0, owner.userEvents().size(), "a revoked share must withhold the event");
            assertEquals(1, adminSink.userEvents().size());
            verify(guard, never()).checkPrincipalUse(anyString(), eq("owner-admin"), any(), anyBoolean());
        }

        @Test
        @DisplayName("an access check that cannot answer delivers nothing")
        void unknownUseCheckFailsClosed() throws Exception {
            var hub = hub();
            var owner = new RecordingSink();
            hub.open(user("owner"), owner);
            when(guard.checkPrincipalUse(anyString(), anyString(), any(), anyBoolean())).thenReturn(ResourceAccessGuard.UseCheck.UNKNOWN);

            hub.fanOut(pending(), UserEventAudience.principal("owner").requiringUse("agent-1"));

            assertEquals(0, owner.userEvents().size());
        }

        @Test
        @DisplayName("an allowed USE check delivers")
        void allowedUseCheck() throws Exception {
            var hub = hub();
            var owner = new RecordingSink();
            hub.open(user("owner"), owner);
            when(guard.checkPrincipalUse(anyString(), anyString(), any(), anyBoolean())).thenReturn(ResourceAccessGuard.UseCheck.ALLOWED);

            hub.fanOut(pending(), UserEventAudience.principal("owner").requiringUse("agent-1"));

            assertEquals(1, owner.userEvents().size());
        }

        @Test
        @DisplayName("an approver reached through the role is not subjected to the owner's USE check")
        void approverNotUseChecked() throws Exception {
            var hub = hub();
            var approverSink = new RecordingSink();
            hub.open(approver("anna"), approverSink);

            hub.fanOut(pending(), UserEventAudience.principal("owner").andApprovers().requiringUse("agent-1"));

            assertEquals(1, approverSink.userEvents().size());
            verify(guard, never()).checkPrincipalUse(anyString(), anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("with authorization off, the one anonymous bucket receives every event")
        void authorizationOff() throws Exception {
            var hub = hub(false, 8, 100, 64);
            var anonymous = new RecordingSink();
            hub.open(new UserEventHub.Subscriber(null, true, true, List.of(), 0), anonymous);

            hub.fanOut(pending(), UserEventAudience.principal("somebody"));

            assertEquals(1, anonymous.userEvents().size());
        }

        @Test
        @DisplayName("with authorization on, a principal-less connection receives nothing")
        void principalLessGetsNothing() throws Exception {
            var hub = hub();
            var nameless = new RecordingSink();
            hub.open(new UserEventHub.Subscriber(null, true, true, List.of(), 0), nameless);

            hub.fanOut(pending(), UserEventAudience.principal("owner").andApprovers());

            assertEquals(0, nameless.userEvents().size());
        }

        @Test
        @DisplayName("yourTurn is true only on the copy of the member whose turn it is")
        void yourTurnPerRecipient() throws Exception {
            var hub = hub();
            var owner = new RecordingSink();
            var member = new RecordingSink();
            hub.open(user("owner"), owner);
            hub.open(user("gregor@example.com"), member);

            hub.fanOut(UserEvent.of(UserEvent.KIND_TEAM_WAITING_FOR_HUMAN).withGroup("g-1", "gc-1"),
                    UserEventAudience.principals(List.of("owner", "gregor@example.com")).withYourTurn("gregor@example.com"));

            assertFalse(JSON.readTree(owner.userEvents().getFirst().data()).get("yourTurn").asBoolean());
            assertTrue(JSON.readTree(member.userEvents().getFirst().data()).get("yourTurn").asBoolean());
        }

        @Test
        @DisplayName("every delivery is counted by kind")
        void deliveredCounted() throws Exception {
            var hub = hub();
            hub.open(user("alice"), new RecordingSink());
            hub.open(user("alice"), new RecordingSink());

            hub.fanOut(pending(), UserEventAudience.principal("alice"));

            assertEquals(2, counter(UserEventHub.METER_DELIVERED, "kind", UserEvent.KIND_APPROVAL_PENDING));
        }
    }

    @Nested
    @DisplayName("payloads")
    class Payloads {

        /** Every field the wire format may carry. Anything else is content. */
        private static final Set<String> ALLOWED = Set.of("id", "kind", "ts", "surface", "conversationId", "agentId", "groupId",
                "groupConversationId", "state", "jobKind", "scheduleId", "notificationId", "notificationType", "yourTurn");

        @Test
        @DisplayName("a delivered event carries ids and kinds only, and its name is its kind")
        void idsOnly() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            hub.open(user("alice"), sink);

            hub.fanOut(pending().withState("APPROVED"), UserEventAudience.principal("alice"));

            var frame = sink.userEvents().getFirst();
            assertEquals(UserEvent.KIND_APPROVAL_PENDING, frame.name());
            JsonNode data = JSON.readTree(frame.data());
            data.fieldNames().forEachRemaining(field -> assertTrue(ALLOWED.contains(field), "unexpected field " + field));
            assertEquals("conv-1", data.get("conversationId").asText());
            assertFalse(data.has("yourTurn"), "absent unless it is a human turn");
        }

        @Test
        @DisplayName("the stream opens with a ready frame stating the heartbeat")
        void readyFrame() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            hub.open(user("alice"), sink);

            var ready = sink.events(UserEventHub.EVENT_READY);
            assertEquals(1, ready.size());
            assertEquals(20, JSON.readTree(ready.getFirst().data()).get("heartbeatSeconds").asInt());
        }
    }

    @Nested
    @DisplayName("caps")
    class Caps {

        @Test
        @DisplayName("the per-user cap refuses the next stream and leaves other users alone")
        void perUserCap() throws Exception {
            var hub = hub(true, 2, 100, 64);
            hub.open(user("alice"), new RecordingSink());
            hub.open(user("alice"), new RecordingSink());

            var refused = assertThrows(UserEventHub.Refused.class, () -> hub.open(user("alice"), new RecordingSink()));
            assertEquals(UserEventHub.REJECT_USER_LIMIT, refused.reason());
            hub.open(user("bob"), new RecordingSink());
            assertEquals(1, counter(UserEventHub.METER_REJECTED, "reason", UserEventHub.REJECT_USER_LIMIT));
            assertEquals(3, hub.connectionCount());
        }

        @Test
        @DisplayName("with authorization off, the shared anonymous bucket is not held to the per-user cap")
        void anonymousBucketExemptFromPerUserCap() throws Exception {
            var hub = hub(false, 2, 100, 64);
            for (int i = 0; i < 5; i++) {
                hub.open(new UserEventHub.Subscriber(null, true, true, List.of(), 0), new RecordingSink());
            }
            assertEquals(5, hub.connectionCount());
            assertEquals(0, counter(UserEventHub.METER_REJECTED, "reason", UserEventHub.REJECT_USER_LIMIT));
        }

        @Test
        @DisplayName("an authenticated user stays held to the per-user cap while anonymous callers are admitted")
        void authenticatedStillCappedBesideAnonymous() throws Exception {
            var hub = hub(true, 2, 100, 64);
            for (int i = 0; i < 4; i++) {
                hub.open(new UserEventHub.Subscriber(null, false, false, List.of(), 0), new RecordingSink());
            }
            hub.open(user("alice"), new RecordingSink());
            hub.open(user("alice"), new RecordingSink());

            var refused = assertThrows(UserEventHub.Refused.class, () -> hub.open(user("alice"), new RecordingSink()));
            assertEquals(UserEventHub.REJECT_USER_LIMIT, refused.reason());
            assertEquals(6, hub.connectionCount());
        }

        @Test
        @DisplayName("the node cap still bounds the anonymous bucket")
        void nodeCapBoundsAnonymous() throws Exception {
            var hub = hub(false, 1, 2, 64);
            var anonymous = new UserEventHub.Subscriber(null, true, true, List.of(), 0);
            hub.open(anonymous, new RecordingSink());
            hub.open(anonymous, new RecordingSink());

            var refused = assertThrows(UserEventHub.Refused.class, () -> hub.open(anonymous, new RecordingSink()));
            assertEquals(UserEventHub.REJECT_NODE_LIMIT, refused.reason());
        }

        @Test
        @DisplayName("a user at the cap whose clients went away can reconnect: dead streams are swept first")
        void deadStreamsDoNotHoldTheCap() throws Exception {
            var hub = hub(true, 2, 100, 64);
            var gone = new RecordingSink();
            var alsoGone = new RecordingSink();
            hub.open(user("alice"), gone);
            hub.open(user("alice"), alsoGone);
            gone.closed = true; // the client disconnected; no write or heartbeat has noticed
            alsoGone.closed = true;

            hub.open(user("alice"), new RecordingSink());
            assertEquals(1, hub.connectionCount("alice"));
            assertEquals(1, hub.connectionCount());
            assertEquals(0, counter(UserEventHub.METER_REJECTED, "reason", UserEventHub.REJECT_USER_LIMIT));
        }

        @Test
        @DisplayName("a user at the cap with only live streams is still refused")
        void liveStreamsHoldTheCap() throws Exception {
            var hub = hub(true, 2, 100, 64);
            hub.open(user("alice"), new RecordingSink());
            hub.open(user("alice"), new RecordingSink());

            assertThrows(UserEventHub.Refused.class, () -> hub.open(user("alice"), new RecordingSink()));
            assertEquals(2, hub.connectionCount("alice"));
        }

        @Test
        @DisplayName("the sweep frees only dead slots and frees a node slot as well")
        void sweepAtNodeCap() throws Exception {
            var hub = hub(true, 8, 2, 64);
            var gone = new RecordingSink();
            hub.open(user("alice"), gone);
            hub.open(user("bob"), new RecordingSink());
            gone.closed = true;

            hub.open(user("alice"), new RecordingSink());
            assertEquals(2, hub.connectionCount());
            assertThrows(UserEventHub.Refused.class, () -> hub.open(user("carol"), new RecordingSink()));
        }

        @Test
        @DisplayName("concurrent opens at one below the cap admit exactly one")
        void concurrentOpensAtCap() throws Exception {
            int cap = 4;
            var hub = hub(true, cap, 100, 64);
            var gone = new RecordingSink();
            hub.open(user("alice"), gone);
            for (int i = 1; i < cap - 1; i++) {
                hub.open(user("alice"), new RecordingSink());
            }
            gone.closed = true; // cap - 1 registered, one of them dead: two slots really free
            hub.open(user("alice"), new RecordingSink()); // now at cap with one dead

            int threads = 16;
            var start = new CountDownLatch(1);
            var admitted = new AtomicInteger();
            var pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            hub.open(user("alice"), new RecordingSink());
                            admitted.incrementAndGet();
                        } catch (UserEventHub.Refused expected) {
                            // at the cap
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, admitted.get());
            assertEquals(cap, hub.connectionCount("alice"));
            assertEquals(cap, hub.connectionCount());
        }

        @Test
        @DisplayName("closing a stream frees its slot")
        void closeFreesSlot() throws Exception {
            var hub = hub(true, 1, 100, 64);
            var first = hub.open(user("alice"), new RecordingSink());
            hub.close(first);
            hub.close(first); // idempotent

            hub.open(user("alice"), new RecordingSink());
            assertEquals(1, hub.connectionCount("alice"));
            assertEquals(1, hub.connectionCount());
        }

        @Test
        @DisplayName("the node cap refuses streams of any user")
        void nodeCap() throws Exception {
            var hub = hub(true, 8, 2, 64);
            hub.open(user("alice"), new RecordingSink());
            hub.open(user("bob"), new RecordingSink());

            var refused = assertThrows(UserEventHub.Refused.class, () -> hub.open(user("carol"), new RecordingSink()));
            assertEquals(UserEventHub.REJECT_NODE_LIMIT, refused.reason());
        }

        @Test
        @DisplayName("a closed stream receives nothing more")
        void closedReceivesNothing() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            var connection = hub.open(user("alice"), sink);
            hub.close(connection);

            hub.fanOut(pending(), UserEventAudience.principal("alice"));

            assertEquals(0, sink.userEvents().size());
            assertTrue(sink.closed);
        }
    }

    @Nested
    @DisplayName("slow consumers")
    class SlowConsumers {

        @Test
        @DisplayName("a client that stops reading is closed once its buffer is full, and counted")
        void bufferOverflowDrops() throws Exception {
            var hub = hub(true, 8, 100, 3);
            var slow = new RecordingSink();
            slow.stalled = true;
            var connection = hub.open(user("alice"), slow); // ready frame: 1 in flight
            var fast = new RecordingSink();
            hub.open(user("alice"), fast);

            for (int i = 0; i < 5; i++) {
                hub.fanOut(pending(), UserEventAudience.principal("alice"));
            }

            assertTrue(connection.isClosed(), "the stalled stream must be dropped");
            assertTrue(slow.closed);
            assertEquals(2, slow.userEvents().size(), "only what fit in the buffer was written");
            assertEquals(5, fast.userEvents().size(), "a healthy tab of the same user is unaffected");
            assertEquals(1, counter(UserEventHub.METER_DROPPED, "reason", UserEventHub.DROP_SLOW_CONSUMER));
            assertEquals(1, hub.connectionCount("alice"));
        }

        @Test
        @DisplayName("a write that fails unregisters the stream")
        void failedWriteUnregisters() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            var connection = hub.open(user("alice"), sink);
            sink.failing = true;

            hub.fanOut(pending(), UserEventAudience.principal("alice"));

            assertTrue(connection.isClosed());
            assertEquals(0, hub.connectionCount());
        }
    }

    @Nested
    @DisplayName("heartbeat")
    class Heartbeat {

        @Test
        @DisplayName("writes a comment to every live stream")
        void writesComment() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            hub.open(user("alice"), sink);

            hub.heartbeat();

            assertTrue(sink.frames.stream().anyMatch(f -> f.name() == null && "keepalive".equals(f.data())));
        }

        @Test
        @DisplayName("closes a stream whose access token expired")
        void closesExpiredToken() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            var connection = hub.open(new UserEventHub.Subscriber("alice", false, false, List.of(), now.get() + 1000), sink);

            now.addAndGet(1000);
            hub.heartbeat();

            assertTrue(connection.isClosed());
            assertEquals(0, hub.connectionCount());
        }

        @Test
        @DisplayName("closes a stream older than the maximum age")
        void closesOldStream() throws Exception {
            var hub = hub();
            var connection = hub.open(user("alice"), new RecordingSink());

            now.addAndGet(Duration.ofHours(1).toMillis());
            hub.heartbeat();

            assertTrue(connection.isClosed());
        }

        @Test
        @DisplayName("unregisters a stream whose client is gone")
        void unregistersClosedSink() throws Exception {
            var hub = hub();
            var sink = new RecordingSink();
            hub.open(user("alice"), sink);
            sink.closed = true;

            hub.heartbeat();

            assertEquals(0, hub.connectionCount());
        }
    }

    @Test
    @DisplayName("a disabled hub delivers nothing")
    void disabled() throws Exception {
        var hub = new UserEventHub(false, 8, 100, 64, 100, Duration.ofSeconds(20), Duration.ofHours(1), new OwnershipValidator(true),
                guardInstance, meters, now::get, false);
        assertFalse(hub.isEnabled());
        var sink = new RecordingSink();
        hub.open(user("alice"), sink);
        hub.dispatch(pending(), UserEventAudience.principal("alice"));
        assertEquals(0, sink.userEvents().size());
    }
}
