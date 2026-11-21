/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;

/**
 * A {@link BlobBackend} test double that holds objects in memory: it buffers
 * (which a real backend must not) but otherwise behaves as the contract says,
 * so tests of the layers above a backend can use it.
 */
public final class InMemoryBlobBackend implements BlobBackend {

    private final Map<String, byte[]> objects;

    public InMemoryBlobBackend() {
        this(new ConcurrentSkipListMap<>());
    }

    /** A backend sharing {@code objects} with another one. */
    InMemoryBlobBackend(Map<String, byte[]> objects) {
        this.objects = objects;
    }

    Map<String, byte[]> objects() {
        return objects;
    }

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public void put(String key, InputStream in, long declaredLength, long maxBytes) throws IOException {
        BlobKeys.requireValidKey(key);
        LengthCheckedInputStream source = new LengthCheckedInputStream(in, declaredLength, maxBytes);
        if (objects.containsKey(key)) {
            throw new FileAlreadyExistsException("A blob with this key already exists");
        }
        byte[] bytes = source.readAllBytes();
        if (objects.putIfAbsent(key, bytes) != null) {
            throw new FileAlreadyExistsException("A blob with this key already exists");
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        return new ByteArrayInputStream(require(key));
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public InputStream getRange(String key, long offset, long length) throws IOException {
        byte[] bytes = require(key);
        BlobStreams.requireValidRange(offset, length);
        int from = (int) Math.min(offset, bytes.length);
        int count = (int) Math.min(length, bytes.length - from);
        return new ByteArrayInputStream(bytes, from, count);
    }

    @Override
    public void delete(String key) {
        objects.remove(BlobKeys.requireValidKey(key));
    }

    @Override
    public Optional<BlobStat> stat(String key) {
        byte[] bytes = objects.get(BlobKeys.requireValidKey(key));
        return bytes == null ? Optional.empty() : Optional.of(new BlobStat(key, bytes.length));
    }

    @Override
    public Stream<String> list(String prefix) {
        BlobKeys.requireValidPrefix(prefix);
        return objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList().stream();
    }

    private byte[] require(String key) throws NoSuchFileException {
        byte[] bytes = objects.get(BlobKeys.requireValidKey(key));
        if (bytes == null) {
            throw new NoSuchFileException("No blob with this key");
        }
        return bytes;
    }
}
