/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Rate-limited progress output for long-running loops, so a migration that
 * works through hundreds of thousands of documents does not look hung.
 * <p>
 * At most one line is written per interval, however many items are processed,
 * so the log volume is bounded by run time rather than by data size. Two ways
 * to drive it:
 * <ul>
 * <li>{@link #advance()} from inside a per-document loop;</li>
 * <li>{@link #startHeartbeat()} around a single long server-side operation (an
 * {@code updateMany}) that has no loop to hook into.</li>
 * </ul>
 * Thread-safe.
 */
public final class ProgressLogger {

    /** Default gap between two progress lines. */
    public static final long DEFAULT_INTERVAL_MILLIS = 10_000L;

    private final String label;
    private final long intervalMillis;
    private final LongSupplier clockMillis;
    private final Consumer<String> sink;
    private final long startedAt;
    private long lastEmittedAt;
    private long count;

    ProgressLogger(String label, long intervalMillis, LongSupplier clockMillis, Consumer<String> sink) {
        this.label = label;
        this.intervalMillis = intervalMillis;
        this.clockMillis = clockMillis;
        this.sink = sink;
        this.startedAt = clockMillis.getAsLong();
        this.lastEmittedAt = startedAt;
    }

    /** A logger that writes an INFO line at most every ten seconds. */
    public static ProgressLogger every10Seconds(Logger logger, String label) {
        return new ProgressLogger(label, DEFAULT_INTERVAL_MILLIS, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), logger::info);
    }

    /** Records one processed item and writes a line if the interval has passed. */
    public void advance() {
        advance(1);
    }

    public synchronized void advance(long items) {
        count += items;
        emitIfDue();
    }

    /** Writes a line if the interval has passed since the last one. */
    public synchronized void emitIfDue() {
        long now = clockMillis.getAsLong();
        if (now - lastEmittedAt >= intervalMillis) {
            lastEmittedAt = now;
            // A heartbeat around one server-side call never advances the count; saying
            // "0 processed" there would be wrong, so report elapsed time only.
            String processed = count > 0 ? count + " processed so far, " : "";
            sink.accept("  " + label + ": still working — " + processed + (now - startedAt) / 1000 + "s elapsed");
        }
    }

    public synchronized long processed() {
        return count;
    }

    /**
     * Starts a daemon thread that calls {@link #emitIfDue()} every second until the
     * returned handle is closed. For one long database call with no loop.
     */
    public Heartbeat startHeartbeat() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eddi-progress-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::emitIfDue, 1, 1, TimeUnit.SECONDS);
        return scheduler::shutdownNow;
    }

    /** Closing stops the heartbeat; never throws. */
    @FunctionalInterface
    public interface Heartbeat extends AutoCloseable {
        @Override
        void close();
    }
}
