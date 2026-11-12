/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A Vert.x response body as a blocking {@link InputStream} — the bridge from
 * the event loop's {@link ReadStream} to {@link OutboundEngine.Received}.
 * <ul>
 * <li><b>Backpressure</b>: buffers arrive on the event loop and queue here; the
 * source is paused once {@value #HIGH_WATER_BYTES} bytes wait, and resumed once
 * the reader has drained them below {@value #LOW_WATER_BYTES}. A slow reader
 * therefore holds at most about one high-water mark plus one chunk.</li>
 * <li><b>Order</b>: data, then the end or the failure, exactly as the source
 * delivered them; a failure surfaces only after the bytes before it were
 * read.</li>
 * <li><b>Close</b>: closing before the end runs {@code abort} — the engine
 * resets the request, which frees the connection — and wakes a reader blocked
 * in {@link #read}, which then fails. {@link OutboundHttp}'s watchdog relies on
 * that to stop a stalled read.</li>
 * </ul>
 * Every call into the source runs on its context (the event loop), never on the
 * reader's thread.
 */
final class VertxBodyStream extends InputStream {

    static final int HIGH_WATER_BYTES = 256 * 1024;
    static final int LOW_WATER_BYTES = 64 * 1024;

    private final ReadStream<Buffer> source;
    private final Runnable abort;
    private final Context context;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
    private long queuedBytes;
    private boolean ended;
    private Throwable failure;
    private boolean closed;
    private boolean paused;

    private Buffer current;
    private int position;

    /**
     * Takes over {@code source}'s handlers and resumes it. Call on the source's
     * context with the source paused, so no buffer arrives before the handlers are
     * in place.
     *
     * @param context
     *            where calls into the source run; {@code null} runs them on the
     *            calling thread (tests)
     * @param abort
     *            stops the exchange when the stream is closed before its end
     */
    VertxBodyStream(ReadStream<Buffer> source, Context context, Runnable abort) {
        this.source = source;
        this.context = context;
        this.abort = abort;
        source.handler(this::onData);
        source.endHandler(ignored -> onEnd());
        source.exceptionHandler(this::onFailure);
        source.resume();
    }

    private void onData(Buffer buffer) {
        lock.lock();
        try {
            if (closed || buffer.length() == 0) {
                return;
            }
            queue.add(buffer);
            queuedBytes += buffer.length();
            if (queuedBytes >= HIGH_WATER_BYTES && !paused) {
                paused = true;
                // On the source's own context already: pause directly, so no further
                // buffer is delivered after this one.
                source.pause();
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void onEnd() {
        lock.lock();
        try {
            ended = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void onFailure(Throwable cause) {
        lock.lock();
        try {
            if (!ended && failure == null) {
                failure = cause;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        boolean resume = false;
        int read;
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    throw new IOException("Stream closed");
                }
                if (current != null && position < current.length()) {
                    break;
                }
                current = queue.poll();
                position = 0;
                if (current != null) {
                    queuedBytes -= current.length();
                    continue;
                }
                if (failure != null) {
                    throw failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure);
                }
                if (ended) {
                    return -1;
                }
                try {
                    changed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while reading the response body");
                }
            }
            read = Math.min(length, current.length() - position);
            current.getBytes(position, position + read, target, offset);
            position += read;
            if (paused && queuedBytes <= LOW_WATER_BYTES) {
                paused = false;
                resume = true;
            }
        } finally {
            lock.unlock();
        }
        if (resume) {
            onContext(source::resume);
        }
        return read;
    }

    @Override
    public int available() {
        lock.lock();
        try {
            long available = queuedBytes + (current != null ? current.length() - position : 0);
            return (int) Math.min(Integer.MAX_VALUE, available);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        boolean incomplete;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            incomplete = !ended && failure == null;
            queue.clear();
            queuedBytes = 0;
            current = null;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (incomplete) {
            onContext(abort);
        }
    }

    private void onContext(Runnable action) {
        if (context == null) {
            action.run();
        } else {
            context.runOnContext(ignored -> action.run());
        }
    }
}
