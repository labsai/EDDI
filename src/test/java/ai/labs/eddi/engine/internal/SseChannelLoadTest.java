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

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
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
        assertEquals("5", String.valueOf(refused.getResponse().getHeaders().getFirst("Retry-After")));
        assertEquals(0, refusedSink.written());
        first.close(SseChannel.CloseReason.COMPLETED);
        capped.open(SseStreams.Kind.AGENT, "d", new FakeSse.Sink(false), sse, 0, 0, null, null);
        assertEquals(2, capped.totalActive());
    }
}
