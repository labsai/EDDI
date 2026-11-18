/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The registry of every server-sent-event stream on this node that carries a
 * conversation, and everything they share: one keep-alive scheduler thread, a
 * bounded disconnect executor, the limits, the capacity cap and the meters.
 * <p>
 * <strong>Capacity.</strong> {@code eddi.streaming.max-concurrent-streams}
 * (default {@value #DEFAULT_MAX_CONCURRENT}, {@code 0} = unlimited) bounds the
 * open streams of this node — agent turns, resumes, group discussions, the
 * OpenAI-compatible {@code /v1} streams and A2A streams together. One over it
 * is refused with {@code 503} and a jittered {@code Retry-After} before its
 * turn starts. The per-user event stream ({@code /me/events}) and the admin
 * streams (logs, cluster, coordinator) are outside it: they have their own
 * bounds.
 * <p>
 * <strong>Buffering.</strong> Besides each stream's own bound,
 * {@code eddi.streaming.max-pending-bytes-total} bounds what all streams
 * together may have handed to their connections unread; a stream that would
 * exceed it is closed for backpressure.
 * <p>
 * <strong>Meters</strong>, resolved once: {@code eddi.streaming.active{kind}}
 * (gauge), {@code eddi.streaming.closed{kind,reason}},
 * {@code eddi.streaming.refused{kind}} and
 * {@code eddi.streaming.first_token{kind}} (timer: stream open to first token).
 */
@ApplicationScoped
public class SseStreams {

    private static final Logger LOGGER = Logger.getLogger(SseStreams.class);

    static final int DEFAULT_MAX_CONCURRENT = 5_000;
    static final int DEFAULT_MAX_PENDING_EVENTS = 512;
    static final long DEFAULT_MAX_PENDING_BYTES = 1_048_576L;
    static final long DEFAULT_MAX_PENDING_BYTES_TOTAL = 268_435_456L;
    static final int DEFAULT_DISCONNECT_CONCURRENCY = 16;
    /** {@code Retry-After} of a stream refused for capacity: 3 to 8 seconds. */
    static final int RETRY_AFTER_MIN_SECONDS = 3;
    static final int RETRY_AFTER_SPREAD_SECONDS = 6;
    /** The {@code code} of the event every open stream gets when the node stops. */
    static final String SERVER_SHUTDOWN_CODE = "server_shutdown";
    /**
     * How long a {@link Slot} waits for the response body that owns it to start
     * before it frees itself: the body normally starts within milliseconds of the
     * resource method returning; one that never does (the client left first) must
     * not hold the slot.
     */
    public static final long SLOT_BODY_START_GRACE_MILLIS = 120_000L;

    /** Which endpoint family a stream belongs to — the {@code kind} tag. */
    public enum Kind {
        AGENT("agent"), GROUP("group"), OPENAI("openai"), A2A("a2a");

        final String tag;

        Kind(String tag) {
            this.tag = tag;
        }
    }

    /** Per-stream buffering limits. */
    record Limits(int maxPendingEvents, long maxPendingBytes) {
    }

    /** Monotonic milliseconds; replaceable in tests. */
    @FunctionalInterface
    interface Clock {
        long millis();
    }

    /**
     * A capacity slot of a stream whose transport is not an {@link SseChannel} (the
     * {@code /v1} and A2A writers). Released exactly once — by its owner, or by the
     * leak guard after its maximum lifetime, for a body that never ran.
     */
    public final class Slot {
        private final Kind kind;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile ScheduledFuture<?> leakGuard;

        private Slot(Kind kind) {
            this.kind = kind;
        }

        /**
         * The body that owns this slot is running and releases it in its own
         * {@code finally}: the leak guard, which only covers a body that never ran, is
         * no longer needed — and must not free the slot of a long stream early.
         */
        public void bodyStarted() {
            ScheduledFuture<?> guard = leakGuard;
            if (guard != null) {
                guard.cancel(false);
            }
        }

        /** The stream ended normally. */
        public void releaseCompleted() {
            release(SseChannel.CloseReason.COMPLETED);
        }

        /** The stream ended with an error. */
        public void releaseFailed() {
            release(SseChannel.CloseReason.FAILED);
        }

        /** The client went away before the stream's end. */
        public void releaseClientGone() {
            release(SseChannel.CloseReason.CLIENT_GONE);
        }

        void release(SseChannel.CloseReason reason) {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> guard = leakGuard;
            if (guard != null) {
                guard.cancel(false);
            }
            releaseSlot(kind, reason);
        }

        boolean isReleased() {
            return released.get();
        }
    }

    private final int maxConcurrent;
    private final Limits limits;
    private final long maxPendingBytesTotal;
    private final Set<SseChannel> open = ConcurrentHashMap.newKeySet();
    /**
     * Open streams of every kind — the number the capacity cap compares against.
     */
    private final AtomicInteger total = new AtomicInteger();
    /** Bytes every channel together has handed to its connection, unread. */
    private final AtomicLong pendingBytesTotal = new AtomicLong();
    private final Map<Kind, AtomicInteger> active = new EnumMap<>(Kind.class);
    private final Map<Kind, Map<SseChannel.CloseReason, Counter>> closedCounters = new EnumMap<>(Kind.class);
    private final Map<Kind, Counter> refusedCounters = new EnumMap<>(Kind.class);
    private final Map<Kind, Timer> firstTokenTimers = new EnumMap<>(Kind.class);

    /**
     * Replaces the process-wide ticker ({@link SharedTicker}) — for tests, which
     * run the ticks by hand. {@code null} uses the shared one.
     */
    volatile ScheduledExecutorService scheduler;

    /**
     * Runs disconnect handling (a cancel's store compare-and-set). Bounded: a proxy
     * restart that drops thousands of streams at once queues their cancels behind
     * {@code eddi.streaming.disconnect-concurrency} workers instead of hitting the
     * datastore with all of them together. Replaceable in tests.
     */
    Executor clientGoneExecutor;

    /** Monotonic clock; replaceable in tests. */
    Clock clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    @Inject
    public SseStreams(@ConfigProperty(name = "eddi.streaming.max-concurrent-streams", defaultValue = "" + DEFAULT_MAX_CONCURRENT) int maxConcurrent,
            @ConfigProperty(name = "eddi.streaming.max-pending-events", defaultValue = "" + DEFAULT_MAX_PENDING_EVENTS) int maxPendingEvents,
            @ConfigProperty(name = "eddi.streaming.max-pending-bytes", defaultValue = "" + DEFAULT_MAX_PENDING_BYTES) long maxPendingBytes,
            @ConfigProperty(name = "eddi.streaming.max-pending-bytes-total",
                            defaultValue = "" + DEFAULT_MAX_PENDING_BYTES_TOTAL) long maxPendingBytesTotal,
            @ConfigProperty(name = "eddi.streaming.disconnect-concurrency",
                            defaultValue = "" + DEFAULT_DISCONNECT_CONCURRENCY) int disconnectConcurrency,
            MeterRegistry meterRegistry) {
        this.maxConcurrent = Math.max(0, maxConcurrent);
        this.limits = new Limits(Math.max(1, maxPendingEvents), Math.max(1L, maxPendingBytes));
        this.maxPendingBytesTotal = Math.max(1L, maxPendingBytesTotal);
        this.clientGoneExecutor = Executors.newFixedThreadPool(Math.max(1, disconnectConcurrency),
                Thread.ofVirtual().name("eddi-sse-client-gone-", 0).factory());
        for (Kind kind : Kind.values()) {
            AtomicInteger count = new AtomicInteger();
            active.put(kind, count);
            Map<SseChannel.CloseReason, Counter> byReason = new EnumMap<>(SseChannel.CloseReason.class);
            if (meterRegistry != null) {
                Gauge.builder("eddi.streaming.active", count, AtomicInteger::get).tag("kind", kind.tag)
                        .description("Open conversation SSE streams on this node").register(meterRegistry);
                for (SseChannel.CloseReason reason : SseChannel.CloseReason.values()) {
                    byReason.put(reason, Counter.builder("eddi.streaming.closed").tag("kind", kind.tag).tag("reason", reason.tag)
                            .description("Conversation SSE streams ended, by reason").register(meterRegistry));
                }
                refusedCounters.put(kind, Counter.builder("eddi.streaming.refused").tag("kind", kind.tag)
                        .description("Streams refused because the node was at eddi.streaming.max-concurrent-streams").register(meterRegistry));
                firstTokenTimers.put(kind, Timer.builder("eddi.streaming.first_token").tag("kind", kind.tag)
                        .description("From stream open to its first token").register(meterRegistry));
            }
            closedCounters.put(kind, byReason);
        }
    }

    /** As the injected constructor, with the default total budget and executor. */
    SseStreams(int maxConcurrent, int maxPendingEvents, long maxPendingBytes, MeterRegistry meterRegistry) {
        this(maxConcurrent, maxPendingEvents, maxPendingBytes, DEFAULT_MAX_PENDING_BYTES_TOTAL, DEFAULT_DISCONNECT_CONCURRENCY,
                meterRegistry);
    }

    /**
     * A registry with the default limits and no meters — for tests and direct
     * construction.
     */
    public static SseStreams unmetered() {
        return new SseStreams(DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_PENDING_EVENTS, DEFAULT_MAX_PENDING_BYTES, null);
    }

    /**
     * Ends every stream still open when the application stops — each with one
     * retryable {@code error} event ({@value #SERVER_SHUTDOWN_CODE}) first, so a
     * client can reconnect to another node instead of seeing a bare end of stream.
     * The shared ticker thread is a daemon and stays.
     */
    @PreDestroy
    void shutdown() {
        for (SseChannel channel : openChannels()) {
            channel.shutdown("{\"message\":\"The server is shutting down; reconnect to continue\",\"code\":\"" + SERVER_SHUTDOWN_CODE
                    + "\",\"retryable\":true}");
        }
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService current = scheduler;
        return current != null ? current : SharedTicker.INSTANCE;
    }

    /**
     * The one daemon thread that ticks every stream of the process, created on
     * first use. Shared by every registry instance, so directly constructed ones
     * (unit tests, the field defaults the beans are built with) start no thread of
     * their own. A daemon, so it never holds up shutdown; each stream cancels its
     * tick when it ends.
     */
    private static final class SharedTicker {
        static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eddi-sse-keepalive");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Opens a stream, or refuses it with {@code 503} and {@code Retry-After} when
     * the node is at capacity — thrown before anything is written, so the client
     * gets a plain HTTP error rather than an event on a 200 stream.
     *
     * @param onClientGone
     *            run once, on the bounded disconnect executor, when the client
     *            vanished or fell too far behind before the stream's terminal
     *            frame; may be {@code null}
     * @param onBackstop
     *            writes the terminal event of a stream silent for longer than
     *            {@code backstopSeconds} after its first event; may be {@code null}
     */
    SseChannel open(Kind kind, String name, SseEventSink sink, Sse sse, long keepAliveSeconds, long backstopSeconds, Runnable onClientGone,
                    Runnable onBackstop) {
        takeCapacity(kind);
        var channel = new SseChannel(this, kind, name, sink, sse, keepAliveSeconds, backstopSeconds, onClientGone, onBackstop);
        open.add(channel);
        active.get(kind).incrementAndGet();
        return channel;
    }

    /**
     * Takes a capacity slot for a stream with its own transport, or refuses it with
     * {@code 503} as {@link #open} does.
     *
     * @param maxLifetimeMillis
     *            when the slot is released even if its owner never does (a response
     *            body that never ran) — unless {@link Slot#bodyStarted()} was
     *            called first; {@code 0} = no guard
     */
    public Slot acquireSlot(Kind kind, long maxLifetimeMillis) {
        takeCapacity(kind);
        active.get(kind).incrementAndGet();
        Slot slot = new Slot(kind);
        if (maxLifetimeMillis > 0) {
            try {
                slot.leakGuard = scheduler().schedule(() -> slot.release(SseChannel.CloseReason.FAILED), maxLifetimeMillis,
                        TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                LOGGER.debugf("SSE slot leak guard not scheduled: %s", e.getMessage());
            }
        }
        return slot;
    }

    private void takeCapacity(Kind kind) {
        while (true) {
            int current = total.get();
            if (maxConcurrent > 0 && current >= maxConcurrent) {
                Counter refused = refusedCounters.get(kind);
                if (refused != null) {
                    refused.increment();
                }
                LOGGER.warnf("Refusing a %s stream: %d streams are open on this node (eddi.streaming.max-concurrent-streams)", kind.tag,
                        current);
                throw new WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .header("Retry-After", retryAfterSeconds()).type(MediaType.APPLICATION_JSON)
                        .entity("{\"code\":\"streams_at_capacity\",\"retryable\":true,"
                                + "\"message\":\"Too many open streams on this server; retry shortly\"}")
                        .build());
            }
            if (total.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    /**
     * Spread out, so the clients a full node refused do not all come back in the
     * same second.
     */
    static int retryAfterSeconds() {
        return RETRY_AFTER_MIN_SECONDS + ThreadLocalRandom.current().nextInt(RETRY_AFTER_SPREAD_SECONDS);
    }

    /** Called by {@link SseChannel#close} exactly once per stream. */
    void release(SseChannel channel, Kind kind, SseChannel.CloseReason reason) {
        if (open.remove(channel)) {
            releaseSlot(kind, reason);
        }
    }

    private void releaseSlot(Kind kind, SseChannel.CloseReason reason) {
        active.get(kind).decrementAndGet();
        total.decrementAndGet();
        Counter counter = closedCounters.get(kind).get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * Reserves {@code bytes} of the node-wide pending budget.
     *
     * @return {@code false} when the budget is spent: the asking stream is closed
     *         for backpressure
     */
    boolean reservePendingBytes(long bytes) {
        long now = pendingBytesTotal.addAndGet(bytes);
        if (now > maxPendingBytesTotal) {
            pendingBytesTotal.addAndGet(-bytes);
            return false;
        }
        return true;
    }

    void releasePendingBytes(long bytes) {
        pendingBytesTotal.addAndGet(-bytes);
    }

    long pendingBytesTotal() {
        return pendingBytesTotal.get();
    }

    void recordFirstToken(Kind kind, long nanos) {
        Timer timer = firstTokenTimers.get(kind);
        if (timer != null) {
            timer.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    ScheduledFuture<?> schedule(Runnable tick, long periodMillis) {
        try {
            return scheduler().scheduleAtFixedRate(tick, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Shutting down: a stream without a keep-alive still works.
            LOGGER.debugf("SSE keep-alive not scheduled: %s", e.getMessage());
            return null;
        }
    }

    Limits limits() {
        return limits;
    }

    Clock clock() {
        return clock;
    }

    Executor clientGoneExecutor() {
        return clientGoneExecutor;
    }

    /** Open streams on this node, all kinds. */
    int totalActive() {
        return total.get();
    }

    /** Open streams of one kind. */
    int active(Kind kind) {
        return active.get(kind).get();
    }

    /** The open streams — for tests that assert cleanup. */
    Set<SseChannel> openChannels() {
        return Set.copyOf(open);
    }
}
