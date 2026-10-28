/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandDocument;

import java.util.Optional;

/**
 * Persistence for the deployment's brand: one document, plus one image per
 * {@link BrandAssetSlot}.
 * <p>
 * Like {@code IGlobalVariableStore} this is operational settings, not a
 * versioned resource — but writes are compare-and-set on the document's
 * revision, so two admins saving at once cannot silently overwrite each other.
 * A MongoDB and a PostgreSQL implementation exist; {@code DataStoreProducers}
 * picks one.
 */
public interface IBrandingStore {

    /** The stored document, or empty when none has ever been written. */
    Optional<BrandDocument> read();

    /**
     * Writes {@code next} if, and only if, the stored revision is still
     * {@code expectedRevision} ({@code 0}: no document exists yet).
     *
     * @return {@code false} when another write got there first
     */
    boolean compareAndSet(long expectedRevision, BrandDocument next);

    /** The image stored for {@code slot}, if any. */
    Optional<BrandAsset> readAsset(BrandAssetSlot slot);

    /** Stores the image for its slot, replacing any previous one. */
    void writeAsset(BrandAsset asset);

    /** Removes the image for {@code slot}. A no-op when there is none. */
    void deleteAsset(BrandAssetSlot slot);
}
