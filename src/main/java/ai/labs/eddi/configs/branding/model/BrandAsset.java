/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * One stored image: its slot, content hash ({@code sha256:<hex>}), the content
 * type decided from its magic bytes, and the bytes themselves.
 */
public record BrandAsset(BrandAssetSlot slot, String hash, String contentType, byte[] data) {

    public BrandAsset {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(data, "data");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BrandAsset other && slot == other.slot && hash.equals(other.hash)
                && contentType.equals(other.contentType) && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slot, hash, contentType, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "BrandAsset[slot=" + slot + ", hash=" + hash + ", contentType=" + contentType + ", bytes=" + data.length + "]";
    }
}
