/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads an HTTP response body from an {@link InputStream} with a hard byte cap
 * and a wall-clock deadline, so an end-user- or LLM-chosen URL cannot exhaust
 * the heap or hold a worker thread indefinitely.
 * <p>
 * The same shape as {@code SafeHttpPageFetcher.readBounded}, lifted here so
 * every outbound path that hands bytes back to the model
 * ({@code AttachmentForwarder}, {@code WebScraperTool}, {@code PdfReaderTool},
 * {@code A2AToolProviderManager}) reads through one bounded, deadline-guarded
 * implementation rather than buffering the whole body with
 * {@code BodyHandlers.ofByteArray()} / {@code ofString()} and checking the size
 * only afterwards.
 */
public final class BoundedBodyReader {

    /** The multiple of the request timeout a body read is allowed to take. */
    private static final int BODY_READ_TIMEOUT_FACTOR = 4;
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(15);
    private static final int BUFFER_SIZE = 8192;

    private BoundedBodyReader() {
        // Utility class
    }

    /**
     * The outcome of a bounded read: the bytes collected (never more than the cap)
     * and whether the body was cut short — because it hit the byte cap or the
     * deadline.
     */
    public record Bounded(byte[] bytes, boolean truncated) {
    }

    /**
     * Reads at most {@code maxBytes} from {@code stream} within a budget derived
     * from {@code requestTimeout}, then stops. Closing the stream early aborts the
     * transfer instead of politely draining a response there is no use for.
     *
     * @param stream
     *            the response body stream (closed by this method)
     * @param maxBytes
     *            the byte cap; non-positive means unbounded (only the deadline
     *            applies)
     * @param requestTimeout
     *            the per-request timeout the body budget is scaled from; null falls
     *            back to a default
     * @param scheduler
     *            a scheduled executor used to interrupt a body read that has
     *            stalled after the headers arrived; may be null, in which case only
     *            the in-loop deadline check applies (a read that blocks forever is
     *            not interrupted, so pass a scheduler for hostile inputs)
     */
    public static Bounded read(InputStream stream, long maxBytes, Duration requestTimeout, ScheduledExecutorService scheduler)
            throws IOException {
        long cap = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        Duration budget = readBudget(requestTimeout);
        long deadlineNanos = System.nanoTime() + budget.toNanos();

        // The check in the loop only runs when a read returns, and a server that
        // sends its headers and then nothing never returns one. Closing the stream
        // from outside is what unblocks a read stuck there: it throws, and what was
        // collected so far comes back as truncated.
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = scheduler == null
                ? null
                : scheduler.schedule(() -> {
                    expired.set(true);
                    closeQuietly(stream);
                }, budget.toMillis(), TimeUnit.MILLISECONDS);

        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try (InputStream body = stream) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (System.nanoTime() > deadlineNanos) {
                    return new Bounded(collected.toByteArray(), true);
                }
                if (total + read > cap) {
                    collected.write(buffer, 0, (int) (cap - total));
                    return new Bounded(collected.toByteArray(), true);
                }
                collected.write(buffer, 0, read);
                total += read;
            }
            return new Bounded(collected.toByteArray(), expired.get());
        } catch (IOException e) {
            if (expired.get()) {
                return new Bounded(collected.toByteArray(), true);
            }
            throw e;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
    }

    /**
     * How long a body may take: a multiple of the per-request timeout, so a large
     * response on a slow link is legitimate while a body that never ends is not.
     */
    static Duration readBudget(Duration requestTimeout) {
        Duration base = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? DEFAULT_BUDGET
                : requestTimeout;
        return base.multipliedBy(BODY_READ_TIMEOUT_FACTOR);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Nothing useful to do: the response is already decided.
        }
    }
}
