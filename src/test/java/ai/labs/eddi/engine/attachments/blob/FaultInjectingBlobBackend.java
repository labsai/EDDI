/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A real {@link BlobBackend} with switches that make it fail — for the store
 * tests of what happens when a backend is unreachable or cannot delete.
 */
public final class FaultInjectingBlobBackend implements BlobBackend {

    private final BlobBackend delegate;
    private volatile boolean failDeletes;
    private volatile boolean failReads;

    public FaultInjectingBlobBackend(BlobBackend delegate) {
        this.delegate = delegate;
    }

    /** Every {@link #delete} fails with an {@link IOException} while set. */
    public void failDeletes(boolean fail) {
        this.failDeletes = fail;
    }

    /**
     * Every {@link #get} and {@link #getRange} fails while set, as an unreachable
     * backend does.
     */
    public void failReads(boolean fail) {
        this.failReads = fail;
    }

    /** The keys the real backend holds, partial objects included. */
    public long keyCount() throws IOException {
        try (Stream<String> keys = delegate.list("")) {
            return keys.count();
        }
    }

    /** The one key the real backend holds; fails unless there is exactly one. */
    public String onlyKey() throws IOException {
        try (Stream<String> keys = delegate.list("")) {
            var all = keys.toList();
            if (all.size() != 1) {
                throw new IllegalStateException("expected one object, found " + all.size());
            }
            return all.get(0);
        }
    }

    /** Removes every object, bypassing the switches. */
    public void clear() throws IOException {
        try (Stream<String> keys = delegate.list("")) {
            for (String key : keys.toList()) {
                delegate.delete(key);
            }
        }
    }

    public BlobBackend delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void put(String key, InputStream in, long declaredLength, long maxBytes) throws IOException {
        delegate.put(key, in, declaredLength, maxBytes);
    }

    @Override
    public InputStream get(String key) throws IOException {
        if (failReads) {
            throw new IOException("injected: backend unreachable");
        }
        return delegate.get(key);
    }

    @Override
    public boolean supportsRange() {
        return delegate.supportsRange();
    }

    @Override
    public InputStream getRange(String key, long offset, long length) throws IOException {
        if (failReads) {
            throw new IOException("injected: backend unreachable");
        }
        return delegate.getRange(key, offset, length);
    }

    @Override
    public void delete(String key) throws IOException {
        if (failDeletes) {
            throw new IOException("injected: delete failed");
        }
        delegate.delete(key);
    }

    @Override
    public Optional<BlobStat> stat(String key) throws IOException {
        return delegate.stat(key);
    }

    @Override
    public Stream<String> list(String prefix) throws IOException {
        return delegate.list(prefix);
    }
}
