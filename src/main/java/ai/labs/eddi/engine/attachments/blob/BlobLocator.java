/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.util.Objects;

/**
 * Where an attachment's bytes live: the {@link BlobBackend#name() backend} and
 * the key within it. Because the locator names its backend, backends coexist:
 * changing the configured backend affects new writes only.
 *
 * @param backend
 *            the backend's name
 * @param key
 *            the opaque key within that backend
 */
public record BlobLocator(String backend, String key) {

    public BlobLocator {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(key, "key");
        if (backend.isBlank() || key.isBlank()) {
            throw new IllegalArgumentException("backend and key must not be blank");
        }
    }
}
