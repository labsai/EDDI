/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.util.Objects;

/**
 * What a {@link BlobBackend} knows about a stored object: its key and size.
 *
 * @param key
 *            the object's key
 * @param sizeBytes
 *            the stored size in bytes
 */
public record BlobStat(String key, long sizeBytes) {

    public BlobStat {
        Objects.requireNonNull(key, "key");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
        }
    }
}
