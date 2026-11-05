/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event-loop-to-blocking bridge against a scripted {@link ReadStream}:
 * order, backpressure, close before the end, and a reader woken by a close.
 */
class VertxBodyStreamTest {

    /** A read stream the test drives by hand, recording pause and resume. */
    static final class ScriptedStream implements ReadStream<Buffer> {
        Handler<Buffer> data;
        Handler<Void> end;
        Handler<Throwable> failure;
        volatile boolean paused = true;
        final AtomicInteger pauses = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();

        void emit(byte[] bytes) {
            data.handle(Buffer.buffer(bytes));
        }

        void finish() {
            end.handle(null);
        }

        void fail(Throwable cause) {
            failure.handle(cause);
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            failure = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            data = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
            pauses.incrementAndGet();
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            paused = false;
            resumes.incrementAndGet();
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            end = handler;
            return this;
        }
    }

    private static byte[] filled(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @Test
    @DisplayName("bytes come out in order, then end-of-stream; the source is resumed once to start")
    void deliversInOrder() throws IOException {
        ScriptedStream source = new ScriptedStream();
        AtomicInteger aborts = new AtomicInteger();
        VertxBodyStream stream = new VertxBodyStream(source, null, aborts::incrementAndGet);
        assertFalse(source.paused);

        source.emit("hello ".getBytes());
        source.emit("world".getBytes());
        source.finish();

        assertEquals("hello world", new String(stream.readAllBytes()));
        assertEquals(-1, stream.read());
        stream.close();
        assertEquals(0, aborts.get(), "a stream read to its end must not reset the request");
    }

    @Test
    @DisplayName("the source is paused at the high-water mark and resumed once drained below the low one")
    void backpressure() throws IOException {
        ScriptedStream source = new ScriptedStream();
        VertxBodyStream stream = new VertxBodyStream(source, null, () -> {
        });
        int chunk = 64 * 1024;
        for (int i = 0; i < VertxBodyStream.HIGH_WATER_BYTES / chunk; i++) {
            source.emit(filled(chunk, i));
        }
        assertTrue(source.paused, "a full queue pauses the source");
        assertEquals(1, source.pauses.get());

        byte[] buffer = new byte[chunk];
        int resumesBefore = source.resumes.get();
        // Drain until at most LOW_WATER remains queued.
        long drained = 0;
        while (drained < VertxBodyStream.HIGH_WATER_BYTES - VertxBodyStream.LOW_WATER_BYTES) {
            drained += stream.read(buffer, 0, buffer.length);
        }
        assertEquals(resumesBefore + 1, source.resumes.get(), "a drained queue resumes the source once");
        assertFalse(source.paused);
    }

    @Test
    @DisplayName("a failure surfaces after the bytes before it, as an IOException")
    void failureAfterData() throws IOException {
        ScriptedStream source = new ScriptedStream();
        VertxBodyStream stream = new VertxBodyStream(source, null, () -> {
        });
        source.emit("ab".getBytes());
        source.fail(new IllegalStateException("connection reset"));

        byte[] buffer = new byte[2];
        assertEquals(2, stream.read(buffer, 0, 2));
        assertArrayEquals("ab".getBytes(), buffer);
        IOException failure = assertThrows(IOException.class, stream::read);
        assertEquals("connection reset", failure.getMessage());
    }

    @Test
    @DisplayName("closing before the end aborts the exchange once and drops what was queued")
    void closeMidBodyAborts() throws IOException {
        ScriptedStream source = new ScriptedStream();
        AtomicInteger aborts = new AtomicInteger();
        VertxBodyStream stream = new VertxBodyStream(source, null, aborts::incrementAndGet);
        source.emit(filled(1000, 1));
        assertEquals(1, stream.read());

        stream.close();
        stream.close();
        source.emit(filled(1000, 2));

        assertEquals(1, aborts.get(), "an early close resets the request exactly once");
        assertEquals(0, stream.available());
        assertThrows(IOException.class, stream::read);
    }

    @Test
    @DisplayName("a reader blocked on an empty queue is woken by a close from another thread and fails")
    void closeWakesBlockedReader() throws Exception {
        ScriptedStream source = new ScriptedStream();
        VertxBodyStream stream = new VertxBodyStream(source, null, () -> {
        });
        CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                stream.read();
                return null;
            } catch (IOException e) {
                return e;
            }
        });
        Thread.sleep(100);
        assertFalse(outcome.isDone(), "the reader waits for data");

        stream.close();

        assertTrue(outcome.get(5, TimeUnit.SECONDS) instanceof IOException);
    }

    @Test
    @DisplayName("a slow consumer still reads every byte of a long body")
    void slowConsumerReadsEverything() throws Exception {
        ScriptedStream source = new ScriptedStream();
        VertxBodyStream stream = new VertxBodyStream(source, null, () -> {
        });
        int chunks = 40;
        int chunk = 32 * 1024;
        CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < chunks; i++) {
                while (source.paused) {
                    Thread.onSpinWait();
                }
                source.emit(filled(chunk, i));
            }
            source.finish();
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, read);
        }
        producer.get(10, TimeUnit.SECONDS);
        assertEquals(chunks * chunk, out.size());
        assertEquals(chunks - 1, out.toByteArray()[out.size() - 1]);
    }
}
