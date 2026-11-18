/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One open server-sent-event stream: the single transport every native SSE
 * endpoint that streams a turn or a discussion writes through (the agent turn,
 * its resume, and the group discussion streams).
 * <p>
 * <strong>What one open stream costs.</strong> No thread: writes are
 * non-blocking ({@link SseEventSink#send} returns a stage), and the keep-alive,
 * the silence backstop and disconnect detection run as one fixed-rate entry on
 * the scheduler every stream shares ({@link SseStreams}). The state is a few
 * atomics, a lock and one scheduled-task handle. Disconnect handling, which
 * reads and writes the store, runs on a virtual thread started only when a
 * client actually leaves.
 * <p>
 * <strong>Bounded buffering.</strong> {@code send} does not wait for the
 * client; Vert.x buffers what the socket cannot take. A client that stops
 * reading — a phone in a tunnel — would otherwise make the server buffer the
 * whole answer, and thousands of them the heap. Every write is counted until
 * its stage completes; past {@link SseStreams.Limits#maxPendingEvents()} events
 * or {@link SseStreams.Limits#maxPendingBytes()} bytes in flight the stream is
 * closed as {@link CloseReason#BACKPRESSURE} and treated as a vanished client
 * (the endpoint's disconnect policy applies), instead of buffering more.
 * <p>
 * <strong>No head-of-line coupling.</strong> The write lock is held only for
 * the non-blocking hand-over to the sink, never across a wait; the shared
 * keep-alive tick only {@code tryLock}s it, so one stream whose write is in
 * progress never delays another stream's heartbeat, backstop or close.
 * <p>
 * <strong>Certain cleanup.</strong> {@link #close(CloseReason)} runs its
 * release — the scheduled tick, the registry slot, the sink — exactly once,
 * whichever path gets there first: completion, error, cancel, disconnect,
 * backpressure, the backstop, or an exception in a listener.
 */
final class SseChannel {

    private static final Logger LOGGER = Logger.getLogger(SseChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The SSE comment text of a keep-alive frame ({@code : keep-alive}). */
    static final String KEEPALIVE_COMMENT = "keep-alive";

    /**
     * Why a stream ended — the {@code reason} tag of {@code eddi.streaming.closed}.
     */
    enum CloseReason {
        COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled"), CLIENT_GONE("client_gone"), IDLE_TIMEOUT("idle_timeout"), BACKPRESSURE(
                "backpressure"),
        /**
         * Opened, then refused before anything was written (a plain HTTP error answered
         * instead).
         */
        REFUSED("refused");

        final String tag;

        CloseReason(String tag) {
            this.tag = tag;
        }
    }

    private final SseStreams streams;
    private final SseStreams.Kind kind;
    private final String name;
    private final SseEventSink sink;
    private final Sse sse;
    private final SseStreams.Clock clock;
    private final long keepAliveMillis;
    private final long backstopMillis;
    private final Runnable onClientGone;
    private final Runnable onBackstop;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicInteger pendingEvents = new AtomicInteger();
    private final AtomicLong pendingBytes = new AtomicLong();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean goneSignalled = new AtomicBoolean();
    private final long openedNanos = System.nanoTime();
    private final AtomicBoolean firstTokenSeen = new AtomicBoolean();

    private volatile long lastWriteMillis;
    private volatile long lastEventMillis;
    private volatile boolean eventSent;
    private volatile ScheduledFuture<?> tick;
    private volatile Runnable onClientGoneNow;

    SseChannel(SseStreams streams, SseStreams.Kind kind, String name, SseEventSink sink, Sse sse, long keepAliveSeconds, long backstopSeconds,
            Runnable onClientGone, Runnable onBackstop) {
        this.streams = streams;
        this.kind = kind;
        this.name = name;
        this.sink = sink;
        this.sse = sse;
        this.clock = streams.clock();
        this.keepAliveMillis = TimeUnit.SECONDS.toMillis(Math.max(0L, keepAliveSeconds));
        this.backstopMillis = keepAliveSeconds > 0 && backstopSeconds > 0 ? TimeUnit.SECONDS.toMillis(backstopSeconds) : 0L;
        this.onClientGone = onClientGone;
        this.onBackstop = onBackstop;
        this.lastWriteMillis = clock.millis();
    }

    /**
     * Starts the shared tick for this stream, unless keep-alive is off. The tick
     * runs every quarter interval and writes a comment only after a whole silent
     * interval, so the longest gap is about 1.25 intervals.
     */
    void startKeepAlive() {
        if (keepAliveMillis <= 0 || released.get()) {
            return;
        }
        long tickMillis = Math.max(1L, keepAliveMillis / 4);
        tick = streams.schedule(this::safeTick, tickMillis);
        if (released.get()) {
            stopTick();
        }
    }

    /**
     * Marks the stream as finishing normally: a sink closing from now on is our own
     * doing.
     */
    void markTerminal() {
        terminal.set(true);
    }

    /**
     * Claims the stream's one terminal event: {@code true} for the first caller
     * only. Every path that writes a terminal frame (completion, error, the
     * backstop, shutdown) claims it first, so a stream never ends with two; a loser
     * only closes.
     */
    boolean tryMarkTerminal() {
        return terminal.compareAndSet(false, true);
    }

    /**
     * Runs on the thread that notices the client is gone, before the queued
     * {@code onClientGone}: for the cheap, in-memory part of a disconnect (raising
     * the turn's cancel flag), so the turn stops spending at once even when the
     * queued store work waits behind thousands of others. Must not block.
     */
    void onClientGoneNow(Runnable now) {
        this.onClientGoneNow = now;
    }

    SseStreams.Kind kind() {
        return kind;
    }

    boolean isTerminal() {
        return terminal.get();
    }

    /**
     * Whether the stream has written one of its own events (proof the turn runs).
     */
    boolean eventSent() {
        return eventSent;
    }

    /** Events handed to the sink whose write has not completed. */
    int pendingEvents() {
        return pendingEvents.get();
    }

    /** Bytes handed to the sink whose write has not completed. */
    long pendingBytes() {
        return Math.max(0L, pendingBytes.get());
    }

    /**
     * {@link #pendingBytes} once the stream is closed: its unsent bytes were
     * returned to the node budget at once, and the writes that settle later must
     * not return them again.
     */
    private static final long CLOSED_MARK = Long.MIN_VALUE / 2;

    /** Counts {@code bytes} as unsent; {@code false} once the stream is closed. */
    private boolean addPending(long bytes) {
        while (true) {
            long current = pendingBytes.get();
            if (current < 0) {
                return false;
            }
            if (pendingBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /**
     * Takes {@code bytes} off the unsent count; {@code false} when the stream was
     * closed meanwhile (its bytes already went back to the node budget).
     */
    private boolean takePending(long bytes) {
        while (true) {
            long current = pendingBytes.get();
            if (current < 0) {
                return false;
            }
            if (pendingBytes.compareAndSet(current, current - bytes)) {
                return true;
            }
        }
    }

    /**
     * Returns everything this stream still holds to the node budget — at close, not
     * when the dead connection's writes eventually settle, so a closed slow stream
     * stops counting against every other stream at once.
     */
    private void returnPendingBytes() {
        long held = pendingBytes.getAndSet(CLOSED_MARK);
        if (held > 0) {
            streams.releasePendingBytes(held);
        }
    }

    boolean isReleased() {
        return released.get();
    }

    /**
     * Writes one named event. Never blocks on the client. Returns {@code false}
     * when the event was not written: the client is gone, the stream is closed, or
     * the client fell so far behind that the stream was closed for backpressure.
     */
    boolean send(String eventName, String data) {
        if (released.get()) {
            return false;
        }
        if (sink.isClosed()) {
            clientGone();
            return false;
        }
        long bytes = data != null ? data.length() + eventName.length() : eventName.length();
        int events = pendingEvents.incrementAndGet();
        long held = pendingBytes();
        SseStreams.Limits limits = streams.limits();
        if (events > limits.maxPendingEvents() || held + bytes > limits.maxPendingBytes()) {
            pendingEvents.decrementAndGet();
            LOGGER.infof("SSE %s: the client fell behind (%d events / %d bytes unsent) — closing the stream instead of buffering more", name,
                    events - 1, held);
            overflow();
            return false;
        }
        // The node budget first, then this stream's count: every byte the stream counts
        // is reserved in the budget, so returning its count at close is exact.
        if (!streams.reservePendingBytes(bytes) && !streams.makeRoomFor(this, bytes)) {
            // This stream is itself the largest holder of the spent node budget: it is
            // shed, without cancelling its turn (see shedForNodeBudget).
            pendingEvents.decrementAndGet();
            shedForNodeBudget();
            return false;
        }
        if (!addPending(bytes)) {
            // Closed meanwhile.
            pendingEvents.decrementAndGet();
            streams.releasePendingBytes(bytes);
            return false;
        }
        boolean written = false;
        CompletionStage<?> stage = null;
        writeLock.lock();
        try {
            OutboundSseEvent event = sse.newEventBuilder().name(eventName).data(String.class, RestAgentEngineStreaming.padDataLines(data))
                    .build();
            stage = sink.send(event);
            written = true;
            long now = clock.millis();
            lastWriteMillis = now;
            lastEventMillis = now;
            eventSent = true;
        } catch (RuntimeException e) {
            // RESTEasy Reactive throws IllegalStateException when the sink closed
            // between the check and the write. Any other failure (a broken payload)
            // is not a disconnect and must not cancel a turn whose client still reads.
            LOGGER.debugf("Failed to send SSE event '%s' on %s: %s", eventName, name, e.getMessage());
        } finally {
            writeLock.unlock();
        }
        settleWhenWritten(stage, bytes);
        if (!written) {
            if (sink.isClosed()) {
                clientGone();
            }
            return false;
        }
        if ("token".equals(eventName) && firstTokenSeen.compareAndSet(false, true)) {
            streams.recordFirstToken(kind, System.nanoTime() - openedNanos);
        }
        return true;
    }

    /**
     * {@link #send} of a payload serialized by Jackson; an unserializable payload
     * sends {@code {}}.
     */
    boolean sendJson(String eventName, Object payload) {
        String data;
        try {
            data = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize '%s' event payload: %s", eventName, e.getMessage());
            data = "{}";
        }
        return send(eventName, data);
    }

    /**
     * Releases the pending count when the sink finished the write. A sink that
     * returns no stage (some test doubles) counts as written at once.
     */
    private void settleWhenWritten(CompletionStage<?> stage, long bytes) {
        if (stage == null) {
            settle(bytes);
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            settle(bytes);
            if (failure != null) {
                try {
                    if (sink.isClosed()) {
                        clientGone();
                    }
                } catch (Throwable t) {
                    close(CloseReason.FAILED);
                }
            }
        });
    }

    private void settle(long bytes) {
        pendingEvents.decrementAndGet();
        if (takePending(bytes)) {
            streams.releasePendingBytes(bytes);
        }
    }

    private void overflow() {
        boolean beforeTerminal = !terminal.get();
        close(CloseReason.BACKPRESSURE);
        if (beforeTerminal) {
            signalGone();
        }
    }

    /**
     * The client is no longer reading. Signals the endpoint once, off this thread.
     */
    void clientGone() {
        boolean beforeTerminal = !terminal.get();
        close(beforeTerminal ? CloseReason.CLIENT_GONE : CloseReason.COMPLETED);
        if (beforeTerminal) {
            signalGone();
        }
    }

    /**
     * Hands the endpoint's disconnect handling to the shared executor — once, and
     * only for a stream that had not reached its terminal frame.
     */
    private void signalGone() {
        if (!goneSignalled.compareAndSet(false, true)) {
            return;
        }
        Runnable now = onClientGoneNow;
        if (now != null) {
            try {
                now.run();
            } catch (Throwable t) {
                LOGGER.debugf("Immediate disconnect handling of %s failed: %s", name, t.getMessage());
            }
        }
        if (onClientGone != null) {
            streams.clientGoneExecutor().execute(onClientGone);
        }
    }

    /**
     * Ends this stream because the node-wide buffer budget is spent and it holds
     * the most of it: closed like a reader that fell behind, but its turn is NOT
     * cancelled — the budget is a property of the node, not a verdict on this
     * client, and the turn finishes and is stored as usual.
     */
    void shedForNodeBudget() {
        LOGGER.infof("SSE %s: holds the most unsent data (%d bytes) while the node-wide buffer budget is spent — closing it; "
                + "its turn continues and is stored", name, pendingBytes.get());
        close(CloseReason.BACKPRESSURE);
    }

    /**
     * The scheduled tick: one stream's failure — a sink that throws from
     * {@code isClosed()} or {@code send}, say — ends that stream properly
     * (released, counted) instead of silently leaving it without keep-alive,
     * backstop and disconnect detection; the shared ticker thread never sees it.
     */
    void safeTick() {
        try {
            keepAliveTick();
        } catch (Throwable t) {
            LOGGER.debugf("SSE tick of %s failed; closing the stream: %s", name, t.getMessage());
            close(CloseReason.FAILED);
        }
    }

    /**
     * Ends the stream with one last event — the node is shutting down — unless it
     * already delivered its terminal frame.
     */
    void shutdown(String errorEventData) {
        if (terminal.compareAndSet(false, true)) {
            try {
                send("error", errorEventData);
            } catch (Throwable t) {
                LOGGER.debugf("Shutdown event of %s failed: %s", name, t.getMessage());
            }
        }
        close(CloseReason.FAILED);
    }

    void keepAliveTick() {
        if (released.get() || terminal.get()) {
            stopTick();
            return;
        }
        long now = clock.millis();
        if (backstopMillis > 0 && eventSent && now - lastEventMillis > backstopMillis) {
            // The error frame is a non-blocking send like any other, so it is written
            // from the tick itself; the turn was already abandoned by the watchdog.
            if (terminal.compareAndSet(false, true) && onBackstop != null) {
                try {
                    onBackstop.run();
                } catch (RuntimeException e) {
                    LOGGER.debugf("Backstop event of %s failed: %s", name, e.getMessage());
                }
            }
            close(CloseReason.IDLE_TIMEOUT);
            return;
        }
        if (now - lastWriteMillis < keepAliveMillis) {
            return;
        }
        if (sink.isClosed()) {
            stopTick();
            // Before the stream's first own event the turn may still be queued behind
            // another turn of the same conversation: a cancel now would hit that turn.
            // Its own first event notices the closed sink and handles it then.
            if (eventSent) {
                clientGone();
            }
            return;
        }
        // tryLock: a write in progress already keeps the connection warm, and a
        // stalled stream must never hold up the shared tick thread.
        if (!writeLock.tryLock()) {
            return;
        }
        CompletionStage<?> stage = null;
        long bytes = KEEPALIVE_COMMENT.length();
        boolean counted = false;
        boolean stalled = false;
        try {
            if (pendingEvents.get() >= streams.limits().maxPendingEvents()) {
                // A silent stream whose client stopped reading: no send() will ever find
                // it over the bound, so the tick ends it at the bound.
                stalled = true;
                return;
            }
            if (!streams.reservePendingBytes(bytes)) {
                // The node budget is spent: skip this heartbeat (the next tick retries).
                // A keep-alive frame is released from the budget when it settles, so it
                // must be reserved in it too.
                return;
            }
            if (!addPending(bytes)) {
                streams.releasePendingBytes(bytes);
                return;
            }
            pendingEvents.incrementAndGet();
            counted = true;
            stage = sink.send(sse.newEventBuilder().comment(KEEPALIVE_COMMENT).build());
            lastWriteMillis = clock.millis();
        } catch (RuntimeException e) {
            LOGGER.debugf("Failed to send SSE keep-alive on %s: %s", name, e.getMessage());
        } finally {
            writeLock.unlock();
            if (stalled) {
                LOGGER.infof("SSE %s: %d events unsent on a silent stream — the client stopped reading; closing it", name,
                        pendingEvents.get());
                if (eventSent) {
                    overflow();
                } else {
                    // Before the stream's first own event its turn may still be queued
                    // behind another turn of the conversation: close, but cancel nothing.
                    close(CloseReason.BACKPRESSURE);
                }
            }
        }
        if (counted) {
            settleWhenWritten(stage, bytes);
        }
        if (sink.isClosed() && eventSent) {
            clientGone();
        }
    }

    private void stopTick() {
        ScheduledFuture<?> future = tick;
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Frees the registry slot of a stream that was refused before it wrote
     * anything, without closing the sink: the endpoint answers a plain HTTP error
     * on it.
     */
    void releaseUnused() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        stopTick();
        returnPendingBytes();
        streams.release(this, kind, CloseReason.REFUSED);
    }

    /**
     * Ends the stream: stops its tick, frees its registry slot and closes the sink
     * — exactly once, whoever calls it first. Safe from any thread and from inside
     * a send.
     */
    void close(CloseReason reason) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            stopTick();
            if (!sink.isClosed()) {
                sink.close();
            }
        } catch (Throwable t) {
            // Whatever the sink throws, the slot below is released.
            LOGGER.debugf("Error closing SSE sink of %s: %s", name, t.getMessage());
        } finally {
            returnPendingBytes();
            streams.release(this, kind, reason);
        }
    }
}
