/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Thousands of simulated streams through the shared SSE transport
 * ({@link SseStreams}, {@link SseChannel}), without sockets: fast readers,
 * readers that stall (their writes never complete), clients that vanish
 * mid-stream, and clients that never read at all. Clock and scheduler are
 * driven by hand, so nothing depends on wall time.
 */
@DisplayName("SSE transport under load")
class SseChannelLoadTest {

    private static final int STREAMS = 4_000;
    private static final int TOKENS = 200;
    private static final int MAX_PENDING_EVENTS = 64;
    private static final long KEEPALIVE_SECONDS = 15;

    private SseStreams streams;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Runnable> ticks = new ArrayList<>();
    private final AtomicInteger liveTicks = new AtomicInteger();
    private final List<Runnable> goneTasks = new ArrayList<>();
    private final FakeSse sse = new FakeSse();

    @BeforeEach
    void setUp() {
        streams = new SseStreams(STREAMS + 10, MAX_PENDING_EVENTS, 1_000_000L, null);
        streams.shutdown();
        streams.clock = now::get;
        streams.clientGoneExecutor = goneTasks::add;
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            ticks.add(invocation.getArgument(0));
            liveTicks.incrementAndGet();
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            AtomicBoolean cancelled = new AtomicBoolean();
            doAnswer(c -> {
                if (cancelled.compareAndSet(false, true)) {
                    liveTicks.decrementAndGet();
                }
                return true;
            }).when(future).cancel(anyBoolean());
            return future;
        }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
        streams.scheduler = scheduler;
    }

    private SseChannel open(SseEventSink sink, Runnable onGone) {
        var channel = streams.open(SseStreams.Kind.AGENT, "stream", sink, sse, KEEPALIVE_SECONDS, 0, onGone, null);
        channel.startKeepAlive();
        return channel;
    }

    @Test
    @DisplayName("thousands of streams: bounded buffers, no stream held up by another, nothing left behind")
    void thousandsOfStreams() {
        List<FakeSse.Sink> sinks = new ArrayList<>();
        List<SseChannel> channels = new ArrayList<>();
        var goneCalls = new AtomicInteger();
        for (int i = 0; i < STREAMS; i++) {
            // i % 4 — 0: healthy, 1: stalls, 2: leaves mid-stream, 3: never reads
            var sink = new FakeSse.Sink(i % 4 == 1 || i % 4 == 3);
            sinks.add(sink);
            channels.add(open(sink, goneCalls::incrementAndGet));
        }
        assertEquals(STREAMS, streams.active(SseStreams.Kind.AGENT));

        int maxPending = 0;
        for (int token = 0; token < TOKENS; token++) {
            for (int i = 0; i < STREAMS; i++) {
                if (i % 4 == 2 && token == TOKENS / 2) {
                    sinks.get(i).close();
                }
                channels.get(i).send("token", "t" + token);
                maxPending = Math.max(maxPending, channels.get(i).pendingEvents());
            }
        }
        assertTrue(maxPending <= MAX_PENDING_EVENTS, "no stream ever buffered more than the limit: " + maxPending);

        // Every healthy stream got every token: the stalled ones held up nobody.
        for (int i = 0; i < STREAMS; i += 4) {
            assertEquals(TOKENS, sinks.get(i).count("token"), "healthy stream " + i);
            assertFalse(channels.get(i).isReleased());
        }
        // Clients that stopped reading were cut off at the limit, not buffered further.
        for (int i = 1; i < STREAMS; i += 2) {
            assertTrue(channels.get(i).isReleased(), "slow stream " + i + " is closed for backpressure");
            assertEquals(MAX_PENDING_EVENTS, sinks.get(i).written());
        }

        // A heartbeat round after a silent interval reaches every live stream.
        now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));
        List.copyOf(ticks).forEach(Runnable::run);
        for (int i = 0; i < STREAMS; i += 4) {
            assertEquals(1, sinks.get(i).comments(), "healthy stream " + i + " got its heartbeat");
        }

        for (int i = 0; i < STREAMS; i += 4) {
            channels.get(i).markTerminal();
            channels.get(i).send("done", "{}");
            channels.get(i).close(SseChannel.CloseReason.COMPLETED);
        }
        goneTasks.forEach(Runnable::run);

        assertEquals(0, streams.totalActive(), "every stream released its slot");
        assertTrue(streams.openChannels().isEmpty());
        assertEquals(0, liveTicks.get(), "every stream's tick was cancelled");
        assertEquals(STREAMS / 4 * 3, goneCalls.get(), "every stream that lost its client was reported, once");
    }

    @Test
    @DisplayName("a stream whose write is stuck never delays another stream's heartbeat or tokens")
    void stuckWriteDoesNotBlockOthers() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        SseEventSink stuck = new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
            }
        };
        var stuckChannel = open(stuck, null);
        var healthySink = new FakeSse.Sink(false);
        var healthy = open(healthySink, null);
        var writer = Thread.ofVirtual().start(() -> stuckChannel.send("token", "x"));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));

            // The shared tick thread runs every stream's tick in turn: the stuck one
            // must return at once (tryLock), so the healthy one gets its heartbeat.
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> List.copyOf(ticks).forEach(Runnable::run));
            healthy.send("token", "y");

            assertEquals(1, healthySink.comments());
            assertEquals(1, healthySink.count("token"));
        } finally {
            release.countDown();
            writer.join();
        }
        stuckChannel.close(SseChannel.CloseReason.COMPLETED);
        healthy.close(SseChannel.CloseReason.COMPLETED);
        assertEquals(0, streams.totalActive());
    }

    @Test
    @DisplayName("every terminal path releases the stream exactly once")
    void everyTerminalPathCleansUp() {
        var paths = EnumSet.allOf(SseChannel.CloseReason.class);
        for (var reason : paths) {
            var sink = new FakeSse.Sink(false);
            var channel = open(sink, null);
            channel.close(reason);
            channel.close(reason);
            channel.close(SseChannel.CloseReason.FAILED);
            assertTrue(sink.isClosed(), reason + " closes the sink");
        }
        // A client that left, noticed by a send and by the tick.
        var gone = new FakeSse.Sink(false);
        var goneChannel = open(gone, null);
        goneChannel.send("token", "a");
        gone.close();
        goneChannel.send("token", "b");
        now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));
        List.copyOf(ticks).forEach(Runnable::run);
        // A listener that throws while the stream ends.
        var throwing = open(new FakeSse.Sink(false), () -> {
            throw new IllegalStateException("listener failed");
        });
        throwing.send("token", "a");
        throwing.clientGone();
        assertThrows(IllegalStateException.class, () -> goneTasks.forEach(Runnable::run));

        assertEquals(0, streams.totalActive());
        assertTrue(streams.openChannels().isEmpty());
        assertEquals(0, liveTicks.get());
    }

    @Test
    @DisplayName("the node refuses a stream over its cap with 503 and Retry-After, before touching the sink")
    void capacityIsBounded() {
        var capped = new SseStreams(2, MAX_PENDING_EVENTS, 1_000_000L, null);
        capped.shutdown();
        capped.scheduler = streams.scheduler;
        var first = capped.open(SseStreams.Kind.AGENT, "a", new FakeSse.Sink(false), sse, 0, 0, null, null);
        capped.open(SseStreams.Kind.GROUP, "b", new FakeSse.Sink(false), sse, 0, 0, null, null);
        var refusedSink = new FakeSse.Sink(false);

        var refused = assertThrows(WebApplicationException.class,
                () -> capped.open(SseStreams.Kind.AGENT, "c", refusedSink, sse, 0, 0, null, null));

        assertEquals(503, refused.getResponse().getStatus());
        int retryAfter = Integer.parseInt(String.valueOf(refused.getResponse().getHeaders().getFirst("Retry-After")));
        assertTrue(retryAfter >= SseStreams.RETRY_AFTER_MIN_SECONDS
                && retryAfter < SseStreams.RETRY_AFTER_MIN_SECONDS + SseStreams.RETRY_AFTER_SPREAD_SECONDS,
                "Retry-After is spread so refused clients do not return together: " + retryAfter);
        assertEquals(0, refusedSink.written());
        first.close(SseChannel.CloseReason.COMPLETED);
        capped.open(SseStreams.Kind.AGENT, "d", new FakeSse.Sink(false), sse, 0, 0, null, null);
        assertEquals(2, capped.totalActive());

        // /v1 and A2A streams count against the same cap, through a slot.
        assertThrows(WebApplicationException.class, () -> capped.acquireSlot(SseStreams.Kind.OPENAI, 0));
    }

    @Test
    @DisplayName("S7: a /v1 or A2A slot is released once, by its owner or by the leak guard of a body that never ran")
    void slotsAreReleasedOnce() {
        List<Runnable> guards = new ArrayList<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            guards.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), any());
        streams.scheduler = scheduler;

        var owned = streams.acquireSlot(SseStreams.Kind.OPENAI, 1_000);
        var orphaned = streams.acquireSlot(SseStreams.Kind.A2A, 1_000);
        assertEquals(1, streams.active(SseStreams.Kind.OPENAI));
        assertEquals(1, streams.active(SseStreams.Kind.A2A));

        owned.bodyStarted();
        owned.releaseCompleted();
        owned.releaseFailed();
        guards.forEach(Runnable::run); // the orphan's guard fires; the owned one's would be a no-op
        orphaned.releaseFailed();

        assertTrue(owned.isReleased());
        assertTrue(orphaned.isReleased());
        assertEquals(0, streams.totalActive());
        assertEquals(0, streams.active(SseStreams.Kind.OPENAI));
        assertEquals(0, streams.active(SseStreams.Kind.A2A));
    }

    @Test
    @DisplayName("the shared tick survives a sink that throws: that stream ends, every other keeps its heartbeat")
    void tickSurvivesAThrowingSink() {
        var broken = new AtomicBoolean();
        SseEventSink throwing = new FakeSse.Sink(false) {
            @Override
            public boolean isClosed() {
                if (broken.get()) {
                    throw new IllegalStateException("connection state unreadable");
                }
                return super.isClosed();
            }
        };
        var bad = open(throwing, null);
        bad.send("token", "a");
        var healthySink = new FakeSse.Sink(false);
        var healthy = open(healthySink, null);
        healthy.send("token", "a");
        broken.set(true);

        for (int round = 1; round <= 3; round++) {
            now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));
            // Each tick is what the shared ticker thread runs; none may throw.
            for (Runnable tick : List.copyOf(ticks)) {
                tick.run();
            }
            assertEquals(round, healthySink.comments(), "the healthy stream got heartbeat " + round);
        }

        assertTrue(bad.isReleased(), "the stream whose sink threw was ended and released");
        assertFalse(healthy.isReleased());
        healthy.close(SseChannel.CloseReason.COMPLETED);
        assertEquals(0, streams.totalActive());
    }

    @Test
    @DisplayName("S6: on shutdown every open stream gets a retryable server_shutdown error, then ends")
    void shutdownAnnouncesItself() {
        List<FakeSse.Sink> sinks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var sink = new FakeSse.Sink(false);
            sinks.add(sink);
            open(sink, null).send("token", "a");
        }
        var finished = new FakeSse.Sink(false);
        var done = open(finished, null);
        done.markTerminal();
        done.send("done", "{}");
        var groupSink = new FakeSse.Sink(false);
        streams.open(SseStreams.Kind.GROUP, "group", groupSink, sse, KEEPALIVE_SECONDS, 0, null, null).send("group_start", "{}");

        streams.shutdown();

        for (var sink : sinks) {
            assertEquals(1, sink.count("error"));
            String data = String.valueOf(sink.events.getLast().data());
            assertTrue(data.contains("\"code\":\"" + SseStreams.SERVER_SHUTDOWN_CODE + "\""), data);
            // M2: the runtime lets in-flight turns finish, so the turn may still be stored.
            assertTrue(data.contains("\"retryable\":false"), data);
            assertTrue(sink.isClosed());
        }
        String groupData = String.valueOf(groupSink.events.getLast().data());
        assertTrue(groupData.contains("\"retryable\":true"), "a group discussion persists round by round: " + groupData);
        assertEquals(0, finished.count("error"), "a stream that already delivered its end gets nothing more");
        assertEquals(0, streams.totalActive());
    }

    @Test
    @DisplayName("S1: a stream has exactly one terminal claim")
    void oneTerminalClaim() {
        var sink = new FakeSse.Sink(false);
        var channel = open(sink, null);

        assertTrue(channel.tryMarkTerminal());
        assertFalse(channel.tryMarkTerminal());
        // A shutdown that comes second adds no second terminal event; it only closes.
        streams.shutdown();
        assertEquals(0, sink.count("error"));
        assertTrue(channel.isReleased());
    }

    @Test
    @DisplayName("M1: keep-alives on thousands of silent streams leave the node-wide byte budget exactly where it was")
    void keepAlivesDoNotDriftTheNodeBudget() {
        List<SseChannel> channels = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            var channel = open(new FakeSse.Sink(false), null);
            channel.send("token", "x");
            channels.add(channel);
        }
        assertEquals(0L, streams.pendingBytesTotal());

        for (int round = 0; round < 20; round++) {
            now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));
            List.copyOf(ticks).forEach(Runnable::run);
        }

        assertEquals(0L, streams.pendingBytesTotal(), "every keep-alive reserved what it released");
        channels.forEach(c -> c.close(SseChannel.CloseReason.COMPLETED));
    }

    @Test
    @DisplayName("M1: a keep-alive on a stalled stream holds its bytes in the node budget until it is written, then returns them")
    void stalledKeepAlivesAreReservedThenReleased() {
        var sink = new FakeSse.Sink(true);
        var channel = open(sink, null);
        channel.send("token", "x");

        for (int round = 0; round < 3; round++) {
            now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));
            List.copyOf(ticks).forEach(Runnable::run);
        }
        long held = streams.pendingBytesTotal();
        assertEquals(channel.pendingBytes(), held, "the node budget holds exactly the stream's unsent bytes");
        assertTrue(held > 0);

        sink.unfinished.forEach(stage -> stage.complete(null));
        assertEquals(0L, streams.pendingBytesTotal());
        channel.close(SseChannel.CloseReason.COMPLETED);
    }

    @Test
    @DisplayName("S5: a silent stream whose client stopped reading is closed by the tick at its bound, and its turn cancelled once")
    void stalledSilentStreamIsClosedAtItsBound() {
        var sink = new FakeSse.Sink(true);
        var gone = new AtomicInteger();
        var channel = open(sink, gone::incrementAndGet);
        channel.send("token", "x");

        for (int round = 0; round < MAX_PENDING_EVENTS + 5; round++) {
            now.addAndGet(TimeUnit.SECONDS.toMillis(KEEPALIVE_SECONDS));
            List.copyOf(ticks).forEach(Runnable::run);
        }
        goneTasks.forEach(Runnable::run);

        assertTrue(channel.isReleased(), "closed for backpressure, not held forever");
        assertEquals(MAX_PENDING_EVENTS, sink.written(), "never more than the bound handed to the connection");
        assertEquals(1, gone.get());
        assertEquals(0, streams.totalActive());
    }

    @Test
    @DisplayName("S6: a spent node budget sheds the stream holding the most, without cancelling its turn; the writer goes on")
    void nodeBudgetShedsTheLargestHolder() {
        var budgeted = new SseStreams(100, 1_000, 1_000_000L, 300L, 1, null);
        budgeted.shutdown();
        budgeted.scheduler = streams.scheduler;
        budgeted.clientGoneExecutor = goneTasks::add;
        var hogGone = new AtomicInteger();
        var writerGone = new AtomicInteger();
        var hogSink = new FakeSse.Sink(true);
        var hog = budgeted.open(SseStreams.Kind.AGENT, "hog", hogSink, sse, 0, 0, hogGone::incrementAndGet, null);
        var writerSink = new FakeSse.Sink(false);
        var writer = budgeted.open(SseStreams.Kind.AGENT, "writer", writerSink, sse, 0, 0, writerGone::incrementAndGet, null);
        // 18 x 15 = 270 of the 300 the node may hold, none of it read by its client.
        for (int i = 0; i < 18; i++) {
            hog.send("token", "0123456789");
        }
        assertEquals(270L, budgeted.pendingBytesTotal());

        assertTrue(writer.send("token", "0123456789abcdefghij0123456789abcdefghij0123456789"), "the healthy writer is not the one kicked");
        goneTasks.forEach(Runnable::run);

        assertTrue(hog.isReleased(), "the largest holder is shed");
        assertFalse(writer.isReleased());
        assertEquals(0, hogGone.get(), "a budget kick never cancels the turn");
        assertEquals(0, writerGone.get());
        assertEquals(0L, budgeted.pendingBytesTotal(), "the shed stream's bytes go back at once, not when its dead writes settle");
        hogSink.unfinished.forEach(stage -> stage.complete(null));
        writer.close(SseChannel.CloseReason.COMPLETED);
        assertEquals(0L, budgeted.pendingBytesTotal());
        assertEquals(0, budgeted.totalActive());
    }

    @Test
    @DisplayName("concurrent senders with writes completing on other threads, some exceptionally: counts and budgets settle to zero")
    void concurrentSendersAndOffThreadCompletions() throws Exception {
        int streamCount = 200;
        int senders = 8;
        int tokens = 100;
        var completer = Executors.newFixedThreadPool(4);
        var failures = new AtomicInteger();
        List<SseChannel> channels = new ArrayList<>();
        try {
            for (int i = 0; i < streamCount; i++) {
                int index = i;
                var sink = new FakeSse.Sink(false) {
                    private final AtomicInteger writes = new AtomicInteger();

                    @Override
                    public CompletionStage<?> send(OutboundSseEvent event) {
                        super.send(event);
                        var stage = new CompletableFuture<Void>();
                        int write = writes.incrementAndGet();
                        completer.execute(() -> {
                            // Every 50th write of every 10th stream fails, as a broken
                            // connection reports it.
                            if (index % 10 == 0 && write % 50 == 0) {
                                failures.incrementAndGet();
                                close();
                                stage.completeExceptionally(new IOException("connection reset"));
                            } else {
                                stage.complete(null);
                            }
                        });
                        return stage;
                    }
                };
                channels.add(open(sink, null));
            }
            var start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int s = 0; s < senders; s++) {
                int offset = s;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int t = 0; t < tokens; t++) {
                        for (int i = offset; i < streamCount; i += senders) {
                            channels.get(i).send("token", "t" + t);
                        }
                    }
                }));
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(60));
            }
            completer.shutdown();
            assertTrue(completer.awaitTermination(60, TimeUnit.SECONDS));

            assertTrue(failures.get() > 0, "some writes failed");
            for (SseChannel channel : channels) {
                assertEquals(0, channel.pendingEvents(), "every write settled, failed ones included");
                assertEquals(0L, channel.pendingBytes());
            }
            assertEquals(0L, streams.pendingBytesTotal(), "the node-wide budget is whole again");
            goneTasks.forEach(Runnable::run);
            for (SseChannel channel : channels) {
                channel.close(SseChannel.CloseReason.COMPLETED);
            }
            assertEquals(0, streams.totalActive());
            assertEquals(0, liveTicks.get());
        } finally {
            completer.shutdownNow();
        }
    }
}
