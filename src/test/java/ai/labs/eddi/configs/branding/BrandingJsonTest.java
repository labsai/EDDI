/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The storage form both stores share. The first run of the store ITs found
 * {@code isEmpty()} serialized as an {@code "empty"} property that the reader
 * then refused — every stored brand was unreadable — so the round trip is
 * pinned here too, where it runs without Docker.
 */
class BrandingJsonTest {

    @Test
    void documentRoundTripsWithOnlyItsOwnFields() {
        BrandDocument document = new BrandDocument(7, "Acme", "A", "t", new BrandColors("#fff", null, null, null, null), true, null,
                BrandAssets.EMPTY.with(BrandAssetSlot.ICON, "sha256:x"), "2026-10-24T10:00:00Z", "admin");
        String json = BrandingJson.write(document);
        assertFalse(json.contains("\"empty\""), json);
        assertFalse(json.contains("\"hasValues\""), json);
        assertEquals(document, BrandingJson.read(json));
        assertEquals(BrandDocument.EMPTY, BrandingJson.read(BrandingJson.write(BrandDocument.EMPTY)));
    }

    @Test
    void unknownFieldsFromALaterVersionAreIgnored() {
        BrandDocument read = BrandingJson.read("{\"revision\":3,\"productName\":\"X\",\"future\":1,"
                + "\"colors\":{\"primary\":\"#fff\",\"future\":2},\"assets\":{\"logo\":\"sha256:a\",\"banner\":\"sha256:b\"}}");
        assertEquals(3, read.revision());
        assertEquals("#fff", read.colors().primary());
        assertEquals("sha256:a", read.assets().logo());
    }
}
