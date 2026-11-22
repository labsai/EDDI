/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A byte store for attachment blobs: bytes in, bytes out, under an opaque key.
 * <p>
 * A backend knows nothing about owners, grants, quotas or file names: those
 * belong to the metadata layer above it, which is the same whichever backend
 * holds the bytes. Keys are opaque and random ({@link BlobKeys#random()}); they
 * never carry a user, team or file name.
 * <p>
 * <b>Never buffers an object.</b> {@link #put} reads its stream chunk by chunk
 * and {@link #get} returns a stream that fetches chunk by chunk, so the heap a
 * transfer needs is bounded by the chunk size, not by the object.
 * <p>
 * <b>Partial objects.</b> A {@code put} that fails (an overrun, a stream
 * shorter than declared, a source or database error) removes what it wrote
 * before it throws. A process that dies mid-{@code put} cannot, so its chunks
 * stay behind: {@link #list} reports such leftovers, which no metadata
 * references (a locator is recorded only after {@code put} returned), and
 * {@link #delete} removes them. What {@link #get} or {@link #stat} say about an
 * object whose {@code put} has not returned is unspecified.
 *
 * @see BlobLocator
 */
public interface BlobBackend {

    /** The name a {@link BlobLocator} records, e.g. {@code gridfs}. */
    String name();

    /**
     * Stores the stream under a new key. Never buffers the object.
     *
     * @param key
     *            a fresh key ({@link BlobKeys#random()}); an existing key is
     *            refused with {@link FileAlreadyExistsException} and the existing
     *            object is left untouched
     * @param in
     *            the bytes; read to its end, not closed
     * @param declaredLength
     *            the exact length, or a negative number when unknown. When known, a
     *            stream longer or shorter than it fails with
     *            {@link BlobLengthException}
     * @param maxBytes
     *            the most this call may store (positive); a longer stream fails
     *            with {@link BlobLengthException}, and a {@code declaredLength}
     *            above it fails before anything is written
     * @throws IOException
     *             on overrun or failure, after the partial object was removed
     */
    void put(String key, InputStream in, long declaredLength, long maxBytes) throws IOException;

    /**
     * Opens the object for reading; the caller closes the stream.
     *
     * @throws NoSuchFileException
     *             if no object has this key. Any other {@link IOException} means
     *             the backend failed, not that the object is absent
     */
    InputStream get(String key) throws IOException;

    /** Whether {@link #getRange} reads only the chunks the range covers. */
    boolean supportsRange();

    /**
     * Opens {@code length} bytes from {@code offset}, clipped at the end of the
     * object; a range starting at or past the end is empty.
     *
     * @throws NoSuchFileException
     *             if no object has this key
     */
    InputStream getRange(String key, long offset, long length) throws IOException;

    /**
     * Removes the object and any chunks a crashed {@code put} left under its key.
     * Idempotent: a missing key is not an error.
     */
    void delete(String key) throws IOException;

    /** The size of a stored object, or empty if there is none. */
    Optional<BlobStat> stat(String key) throws IOException;

    /**
     * Keys starting with {@code prefix} (empty for all), including those of partial
     * objects a crashed {@code put} left. The stream may hold a cursor or a
     * connection: close it (try-with-resources).
     */
    Stream<String> list(String prefix) throws IOException;
}
