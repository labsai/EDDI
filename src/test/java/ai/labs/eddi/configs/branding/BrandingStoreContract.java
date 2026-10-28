/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandDocument;

import java.util.Arrays;

import static ai.labs.eddi.configs.branding.BrandingTestSupport.PNG;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.svg;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link IBrandingStore} must do, run against MongoDB and PostgreSQL
 * by {@code BrandingStoreMongoIT} and {@code BrandingStorePostgresIT} — through
 * the real persistence boundary, since an in-memory store cannot show a field
 * the database drops.
 */
public final class BrandingStoreContract {

    private BrandingStoreContract() {
    }

    public static void emptyStoreHasNoBrand(IBrandingStore store) {
        assertTrue(store.read().isEmpty());
        assertTrue(store.readAsset(BrandAssetSlot.LOGO).isEmpty());
    }

    /** Every field, including the nested ones, comes back as written. */
    public static void documentRoundTrips(IBrandingStore store) {
        BrandDocument written = new BrandDocument(1, "Acme Assist", "Acme", "AI teams for Acme",
                new BrandColors("#0f766e", "#ffffff", "#2dd4bf", "#042f2e", "oklch(0.5 0.1 180)"), false, true,
                BrandAssets.EMPTY.with(BrandAssetSlot.LOGO, "sha256:abc").with(BrandAssetSlot.OG_IMAGE, "sha256:def"),
                "2026-10-24T10:00:00Z", "admin");
        assertTrue(store.compareAndSet(0, written));
        assertEquals(written, store.read().orElseThrow());
    }

    public static void compareAndSetRefusesAStaleRevision(IBrandingStore store) {
        BrandDocument first = new BrandDocument(1, "One", null, null, null, null, null, null, null, null);
        assertTrue(store.compareAndSet(0, first));
        assertFalse(store.compareAndSet(0, first.stamped(1, null, "late")), "a second create must lose");

        BrandDocument second = new BrandDocument(2, "Two", null, null, null, null, null, null, null, null);
        assertTrue(store.compareAndSet(1, second));
        assertFalse(store.compareAndSet(1, new BrandDocument(2, "Lost", null, null, null, null, null, null, null, null)));
        assertEquals("Two", store.read().orElseThrow().productName());
        assertEquals(2, store.read().orElseThrow().revision());

        BrandDocument reset = BrandDocument.EMPTY.stamped(3, null, null);
        assertTrue(store.compareAndSet(2, reset));
        assertFalse(store.read().orElseThrow().hasValues());
        assertEquals(3, store.read().orElseThrow().revision());
    }

    public static void assetsAreStoredPerSlot(IBrandingStore store) {
        BrandAsset logo = BrandAssetValidator.validate(BrandAssetSlot.LOGO, PNG);
        BrandAsset icon = BrandAssetValidator.validate(BrandAssetSlot.ICON, svg("<rect/>"));
        store.writeAsset(logo);
        store.writeAsset(icon);
        assertEquals(logo, store.readAsset(BrandAssetSlot.LOGO).orElseThrow());
        assertEquals(icon, store.readAsset(BrandAssetSlot.ICON).orElseThrow());

        byte[] bigger = Arrays.copyOf(PNG, BrandAssetValidator.MAX_BYTES);
        BrandAsset replaced = BrandAssetValidator.validate(BrandAssetSlot.LOGO, bigger);
        store.writeAsset(replaced);
        assertArrayEquals(bigger, store.readAsset(BrandAssetSlot.LOGO).orElseThrow().data());
        assertEquals(replaced.hash(), store.readAsset(BrandAssetSlot.LOGO).orElseThrow().hash());

        store.deleteAsset(BrandAssetSlot.LOGO);
        store.deleteAsset(BrandAssetSlot.LOGO);
        assertTrue(store.readAsset(BrandAssetSlot.LOGO).isEmpty());
        assertTrue(store.readAsset(BrandAssetSlot.ICON).isPresent());
    }
}
