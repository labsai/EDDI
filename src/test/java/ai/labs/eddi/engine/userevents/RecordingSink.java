/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A {@link UserEventSink} that records frames; writes can be held back. */
final class RecordingSink implements UserEventSink {

    record Frame(String name, String data) {
    }

    final List<Frame> frames = new ArrayList<>();
    final List<CompletableFuture<Void>> pending = new ArrayList<>();
    /** When true, writes never complete — a client that stopped reading. */
    boolean stalled;
    /** When true, writes fail — a client that went away. */
    boolean failing;
    boolean closed;

    @Override
    public synchronized CompletionStage<?> send(String name, String data) {
        frames.add(new Frame(name, data));
        return outcome();
    }

    @Override
    public synchronized CompletionStage<?> comment(String text) {
        frames.add(new Frame(null, text));
        return outcome();
    }

    private CompletionStage<?> outcome() {
        if (failing) {
            return CompletableFuture.failedFuture(new IllegalStateException("client gone"));
        }
        if (stalled) {
            var future = new CompletableFuture<Void>();
            pending.add(future);
            return future;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    synchronized List<Frame> events(String name) {
        return frames.stream().filter(f -> name.equals(f.name())).toList();
    }

    synchronized List<Frame> userEvents() {
        return frames.stream().filter(f -> f.name() != null && !UserEventHub.EVENT_READY.equals(f.name())).toList();
    }
}
