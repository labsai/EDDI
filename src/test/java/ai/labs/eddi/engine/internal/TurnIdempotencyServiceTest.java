/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.labs.eddi.engine.internal.TurnIdempotencyService.Admission;
import ai.labs.eddi.engine.internal.TurnIdempotencyService.InvalidIdempotencyKeyException;
import ai.labs.eddi.engine.internal.TurnIdempotencyService.Kind;
import ai.labs.eddi.engine.internal.TurnIdempotencyService.Outcome;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.LlmFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Turn idempotency")
class TurnIdempotencyServiceTest {

    private final TurnIdempotencyService service = new TurnIdempotencyService(600, 30, new SimpleMeterRegistry(), System::currentTimeMillis);

    private static SimpleConversationMemorySnapshot snapshot(ConversationState state) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(state);
        return snapshot;
    }

    @Test
    @DisplayName("first sight owns the key, a second request waits on it, scoped to the conversation")
    void ownerThenWaiterAndScope() throws Exception {
        assertEquals(Kind.OWNER, service.admit("c1", "k", () -> null).kind());
        Admission second = service.admit("c1", "k", () -> null);
        assertEquals(Kind.WAIT, second.kind());
        assertEquals(Kind.OWNER, service.admit("c2", "k", () -> null).kind(), "the same key on another conversation is another turn");
    }

    @Test
    @DisplayName("completing wakes the waiter with the same outcome and leaves a replayable copy")
    void completeWakesAndReplays() throws Exception {
        service.admit("c1", "k", () -> null);
        Admission waiter = service.admit("c1", "k", () -> null);
        var outcome = new Outcome(snapshot(ConversationState.READY), false);

        service.complete("c1", "k", outcome);

        assertSame(outcome, waiter.future().get());
        Admission replay = service.admit("c1", "k", () -> null);
        assertEquals(Kind.REPLAY, replay.kind());
        assertSame(outcome, replay.outcome());
        assertEquals(0, service.inFlightCount(), "nothing stays tracked as running");
    }

    @Test
    @DisplayName("a failed turn wakes its waiters but is not replayable")
    void failedTurnNotReplayable() throws Exception {
        service.admit("c1", "k", () -> null);
        Admission waiter = service.admit("c1", "k", () -> null);
        var failed = new Outcome(snapshot(ConversationState.ERROR), false);

        service.complete("c1", "k", failed);

        assertSame(failed, waiter.future().get());
        assertEquals(Kind.OWNER, service.admit("c1", "k", () -> null).kind());
    }

    @Test
    @DisplayName("abandoning releases the key and fails the waiters")
    void abandonReleases() throws Exception {
        service.admit("c1", "k", () -> null);
        Admission waiter = service.admit("c1", "k", () -> null);

        service.abandon("c1", "k", new IllegalStateException("refused"));

        assertTrue(waiter.future().isCompletedExceptionally());
        assertEquals(Kind.OWNER, service.admit("c1", "k", () -> null).kind());
    }

    @Test
    @DisplayName("a persisted turn is replayed")
    void persistedReplay() throws Exception {
        var stored = new Outcome(snapshot(ConversationState.READY), false);
        Admission admission = service.admit("c1", "k", () -> stored);
        assertEquals(Kind.REPLAY, admission.kind());
        assertSame(stored, admission.outcome());
    }

    @Test
    @DisplayName("a duplicate of a running turn is answered asynchronously, with the owner's snapshot")
    void waiterAnsweredWhenTheTurnFinishes() throws Exception {
        assertTrue(service.admitOrServe("c1", "k", null, () -> null, sink(new AtomicReference<>()), () -> null, () -> {
        }));
        var received = new AtomicReference<SimpleConversationMemorySnapshot>();
        assertFalse(service.admitOrServe("c1", "k", null, () -> null, sink(received), () -> null, () -> {
        }));
        assertNull(received.get());

        var done = snapshot(ConversationState.READY);
        service.complete("c1", "k", new Outcome(done, false));

        assertSame(done, received.get());
    }

    @Test
    @DisplayName("a duplicate whose budget runs out gets the conversation as it stands (409 territory), not a hang")
    void waiterTimesOut() throws Exception {
        service.admitOrServe("c1", "k", null, () -> null, sink(new AtomicReference<>()), () -> null, () -> {
        });
        var skipped = new AtomicReference<SimpleConversationMemorySnapshot>();
        var current = snapshot(ConversationState.IN_PROGRESS);
        var sink = new TurnIdempotencyService.SnapshotSink() {
            @Override
            public void done(SimpleConversationMemorySnapshot s) {
                fail("must not complete");
            }

            @Override
            public void skipped(SimpleConversationMemorySnapshot s) {
                skipped.set(s);
            }
        };

        service.admitOrServe("c1", "k", 50L, () -> null, sink, () -> current, () -> {
        });

        long until = System.currentTimeMillis() + 3000;
        while (skipped.get() == null && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertSame(current, skipped.get());
    }

    @Test
    @DisplayName("a retry that fails still answers the waiter, with the current state")
    void failedRetryStillAnswers() throws Exception {
        service.admitOrServe("c1", "k", null, () -> null, sink(new AtomicReference<>()), () -> null, () -> {
        });
        var skipped = new AtomicReference<SimpleConversationMemorySnapshot>();
        var current = snapshot(ConversationState.READY);
        var sink = new TurnIdempotencyService.SnapshotSink() {
            @Override
            public void done(SimpleConversationMemorySnapshot s) {
                fail("must not complete");
            }

            @Override
            public void skipped(SimpleConversationMemorySnapshot s) {
                skipped.set(s);
            }
        };
        service.admitOrServe("c1", "k", null, () -> null, sink, () -> current, () -> {
            throw new IllegalStateException("shutting down");
        });

        service.abandon("c1", "k", new IllegalStateException("refused"));

        assertSame(current, skipped.get());
    }

    @Test
    @DisplayName("the wait budget is the caller's deadline, never above the agent timeout")
    void waitBudget() {
        assertEquals(30_000, service.waitBudgetMs(null));
        assertEquals(5_000, service.waitBudgetMs(5_000L));
        assertEquals(30_000, service.waitBudgetMs(900_000L));
    }

    @Test
    @DisplayName("a TTL of zero switches the feature off")
    void zeroTtlDisables() {
        assertFalse(new TurnIdempotencyService(0, 30, null, System::currentTimeMillis).isEnabled());
        assertTrue(service.isEnabled());
    }

    @Test
    @DisplayName("keys: null passes, printable ASCII up to 128 characters is accepted, the rest is refused")
    void keyValidation() {
        assertNull(TurnIdempotencyService.validateKey(null));
        assertEquals("abc-123_.:/", TurnIdempotencyService.validateKey("abc-123_.:/"));
        assertEquals("k".repeat(128), TurnIdempotencyService.validateKey("k".repeat(128)));
        assertThrows(InvalidIdempotencyKeyException.class, () -> TurnIdempotencyService.validateKey("k".repeat(129)));
        assertThrows(InvalidIdempotencyKeyException.class, () -> TurnIdempotencyService.validateKey("  "));
        assertThrows(InvalidIdempotencyKeyException.class, () -> TurnIdempotencyService.validateKey("abc "));
        assertThrows(InvalidIdempotencyKeyException.class, () -> TurnIdempotencyService.validateKey(" abc"));
        assertEquals("a b", TurnIdempotencyService.validateKey("a b"));
        assertThrows(InvalidIdempotencyKeyException.class, () -> TurnIdempotencyService.validateKey("a\nb"));
        assertThrows(InvalidIdempotencyKeyException.class, () -> TurnIdempotencyService.validateKey("café"));
    }

    @Test
    @DisplayName("the header reader prefers Idempotency-Key over the alias and rejects a bad key")
    void headerReader() {
        var input = new InputData();
        reader("primary", "alias").apply(input);
        assertEquals("primary", input.getIdempotencyKey());

        input = new InputData();
        reader(null, "alias").apply(input);
        assertEquals("alias", input.getIdempotencyKey());

        input = new InputData();
        reader(null, null).apply(input);
        assertNull(input.getIdempotencyKey());

        assertThrows(InvalidIdempotencyKeyException.class, () -> reader("k".repeat(200), null).apply(new InputData()));
    }

    @Test
    @DisplayName("a request body cannot carry the key")
    void bodyCannotSetKey() throws Exception {
        var mapper = new ObjectMapper();
        InputData parsed = mapper.readValue("{\"input\":\"hi\",\"idempotencyKey\":\"sneaky\"}", InputData.class);
        assertNull(parsed.getIdempotencyKey());
    }

    @Test
    @DisplayName("TurnError: a recognised class keeps its name and Retry-After, an unknown one is generic")
    void turnError() {
        var limited = TurnError.of(new LlmFailure(FailureClass.RATE_LIMITED, 2_500L, "r"), "msg");
        assertEquals("RATE_LIMITED", limited.code());
        assertTrue(limited.retryable());
        assertEquals(3L, limited.retryAfterSeconds());

        var unknown = TurnError.of(new LlmFailure(FailureClass.UNKNOWN, null, "r"), "msg");
        assertEquals(TurnError.TURN_FAILED, unknown.code());
        assertFalse(unknown.retryable());
        assertNull(unknown.retryAfterSeconds());

        var quota = TurnError.of(new LlmFailure(FailureClass.QUOTA_EXHAUSTED, null, "r"), "msg");
        assertFalse(quota.retryable());
    }

    private static TurnIdempotencyService.SnapshotSink sink(AtomicReference<SimpleConversationMemorySnapshot> into) {
        return new TurnIdempotencyService.SnapshotSink() {
            @Override
            public void done(SimpleConversationMemorySnapshot s) {
                into.set(s);
            }

            @Override
            public void skipped(SimpleConversationMemorySnapshot s) {
                fail("unexpected skip");
            }
        };
    }

    private static IdempotencyKeyHeaderReader reader(String primary, String alias) {
        var routing = mock(RoutingContext.class);
        var request = mock(HttpServerRequest.class);
        when(routing.request()).thenReturn(request);
        when(request.getHeader(IdempotencyKeyHeaderReader.HEADER)).thenReturn(primary);
        when(request.getHeader(IdempotencyKeyHeaderReader.ALIAS_HEADER)).thenReturn(alias);
        var current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenReturn(routing);
        return new IdempotencyKeyHeaderReader(current);
    }
}
