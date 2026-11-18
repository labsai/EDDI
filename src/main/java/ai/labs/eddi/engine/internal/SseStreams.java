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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The registry of open native SSE streams on this node, and everything they
 * share: one keep-alive scheduler thread, the disconnect executor, the limits,
 * the capacity cap and the meters.
 * <p>
 * <strong>Capacity.</strong> {@code eddi.streaming.max-concurrent-streams}
 * (default {@value #DEFAULT_MAX_CONCURRENT}, {@code 0} = unlimited) bounds the
 * open streams of this node. One over it is refused with {@code 503} and
 * {@code Retry-After} before its sink is touched, rather than every stream on
 * the node slowing down.
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
    /** {@code Retry-After} of a stream refused for capacity, in seconds. */
    static final int RETRY_AFTER_SECONDS = 5;

    /** Which endpoint family a stream belongs to — the {@code kind} tag. */
    enum Kind {
        AGENT("agent"), GROUP("group");

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

    private final int maxConcurrent;
    private final Limits limits;
    private final Set<SseChannel> open = ConcurrentHashMap.newKeySet();
    /**
     * Open streams of every kind — the number the capacity cap compares against.
     */
    private final AtomicInteger total = new AtomicInteger();
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
     * Runs disconnect handling (store reads and writes), one virtual thread per
     * actual disconnect.
     */
    Executor clientGoneExecutor = runnable -> Thread.ofVirtual().name("eddi-sse-client-gone").start(runnable);

    /** Monotonic clock; replaceable in tests. */
    Clock clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    @Inject
    public SseStreams(@ConfigProperty(name = "eddi.streaming.max-concurrent-streams", defaultValue = "" + DEFAULT_MAX_CONCURRENT) int maxConcurrent,
            @ConfigProperty(name = "eddi.streaming.max-pending-events", defaultValue = "" + DEFAULT_MAX_PENDING_EVENTS) int maxPendingEvents,
            @ConfigProperty(name = "eddi.streaming.max-pending-bytes", defaultValue = "" + DEFAULT_MAX_PENDING_BYTES) long maxPendingBytes,
            MeterRegistry meterRegistry) {
        this.maxConcurrent = Math.max(0, maxConcurrent);
        this.limits = new Limits(Math.max(1, maxPendingEvents), Math.max(1L, maxPendingBytes));
        for (Kind kind : Kind.values()) {
            AtomicInteger count = new AtomicInteger();
            active.put(kind, count);
            Map<SseChannel.CloseReason, Counter> byReason = new EnumMap<>(SseChannel.CloseReason.class);
            if (meterRegistry != null) {
                Gauge.builder("eddi.streaming.active", count, AtomicInteger::get).tag("kind", kind.tag)
                        .description("Open native SSE streams on this node").register(meterRegistry);
                for (SseChannel.CloseReason reason : SseChannel.CloseReason.values()) {
                    byReason.put(reason, Counter.builder("eddi.streaming.closed").tag("kind", kind.tag).tag("reason", reason.tag)
                            .description("Native SSE streams ended, by reason").register(meterRegistry));
                }
                refusedCounters.put(kind, Counter.builder("eddi.streaming.refused").tag("kind", kind.tag)
                        .description("Streams refused because the node was at eddi.streaming.max-concurrent-streams").register(meterRegistry));
                firstTokenTimers.put(kind, Timer.builder("eddi.streaming.first_token").tag("kind", kind.tag)
                        .description("From stream open to its first token").register(meterRegistry));
            }
            closedCounters.put(kind, byReason);
        }
    }

    /**
     * A registry with the default limits and no meters — for tests and direct
     * construction.
     */
    static SseStreams unmetered() {
        return new SseStreams(DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_PENDING_EVENTS, DEFAULT_MAX_PENDING_BYTES, null);
    }

    /**
     * Ends every stream still open when the application stops, so no tick outlives
     * it. The shared ticker thread is a daemon and stays.
     */
    @PreDestroy
    void shutdown() {
        for (SseChannel channel : openChannels()) {
            channel.close(SseChannel.CloseReason.FAILED);
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
     *            run once, on a virtual thread, when the client vanished or fell
     *            too far behind before the stream's terminal frame; may be
     *            {@code null}
     * @param onBackstop
     *            writes the terminal event of a stream silent for longer than
     *            {@code backstopSeconds} after its first event; may be {@code null}
     */
    SseChannel open(Kind kind, String name, SseEventSink sink, Sse sse, long keepAliveSeconds, long backstopSeconds, Runnable onClientGone,
                    Runnable onBackstop) {
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
                        .header("Retry-After", RETRY_AFTER_SECONDS).type(MediaType.APPLICATION_JSON)
                        .entity("{\"code\":\"streams_at_capacity\",\"retryable\":true,"
                                + "\"message\":\"Too many open streams on this server; retry shortly\"}")
                        .build());
            }
            if (total.compareAndSet(current, current + 1)) {
                break;
            }
        }
        var channel = new SseChannel(this, kind, name, sink, sse, keepAliveSeconds, backstopSeconds, onClientGone, onBackstop);
        open.add(channel);
        active.get(kind).incrementAndGet();
        return channel;
    }

    /** Called by {@link SseChannel#close} exactly once per stream. */
    void release(SseChannel channel, Kind kind, SseChannel.CloseReason reason) {
        if (open.remove(channel)) {
            active.get(kind).decrementAndGet();
            total.decrementAndGet();
        }
        Counter counter = closedCounters.get(kind).get(reason);
        if (counter != null) {
            counter.increment();
        }
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
