/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The installable Workforce's manifest (T1.11): branded, scoped, never cached
 * data.
 */
class WorkforceManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /**
     * A PNG header (signature + IHDR) of the given size; the rest does not matter
     * here.
     */
    private static byte[] pngOf(int width, int height) {
        byte[] data = new byte[33];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        System.arraycopy(signature, 0, data, 0, 8);
        data[11] = 13;
        data[12] = 'I';
        data[13] = 'H';
        data[14] = 'D';
        data[15] = 'R';
        data[16] = (byte) (width >>> 24);
        data[17] = (byte) (width >>> 16);
        data[18] = (byte) (width >>> 8);
        data[19] = (byte) width;
        data[20] = (byte) (height >>> 24);
        data[21] = (byte) (height >>> 16);
        data[22] = (byte) (height >>> 8);
        data[23] = (byte) height;
        return data;
    }

    @Test
    @DisplayName("the default brand: EDDI Workforce, standalone, scoped to /workforce, the bundled SVG icon at any size")
    void defaultBrand() throws Exception {
        BrandAsset svg = new BrandAsset(BrandAssetSlot.ICON, "h1", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        JsonNode manifest = parse(WorkforceManifest.render(ResolvedBrand.defaults(false), Optional.of(svg)));

        assertEquals("EDDI Workforce", manifest.get("name").asText());
        assertEquals("EDDI", manifest.get("short_name").asText());
        assertEquals("/workforce", manifest.get("start_url").asText());
        assertEquals("/workforce", manifest.get("scope").asText());
        assertEquals("/workforce", manifest.get("id").asText());
        assertEquals("standalone", manifest.get("display").asText());
        assertEquals("#111113", manifest.get("theme_color").asText());
        JsonNode icon = manifest.get("icons").get(0);
        assertEquals("/branding/icon", icon.get("src").asText());
        assertEquals("image/svg+xml", icon.get("type").asText());
        assertEquals("any", icon.get("sizes").asText());
    }

    @Test
    @DisplayName("a brand's name and primary colour; a PNG icon names its real size")
    void customBrand() throws Exception {
        ResolvedBrand brand = new ResolvedBrand(3, BrandSource.DATABASE, false, true, "Acme Assist", "Acme", null,
                new BrandColors("#0055aa", null, null, null, null), false, false, BrandAssets.EMPTY);
        BrandAsset png = new BrandAsset(BrandAssetSlot.ICON, "h2", "image/png", pngOf(512, 512));
        JsonNode manifest = parse(WorkforceManifest.render(brand, Optional.of(png)));

        assertEquals("Acme Assist Workforce", manifest.get("name").asText());
        assertEquals("Acme", manifest.get("short_name").asText());
        assertEquals("#0055aa", manifest.get("theme_color").asText());
        assertEquals("512x512", manifest.get("icons").get(0).get("sizes").asText());
        assertFalse(manifest.toString().contains("EDDI"), "nothing of the default brand leaks into a branded manifest");
    }

    @Test
    @DisplayName("a name with quotes stays valid JSON; no icon means no icons entry")
    void escapingAndNoIcon() throws Exception {
        ResolvedBrand brand = new ResolvedBrand(4, BrandSource.DATABASE, false, true, "Say \"Hi\"", null, null,
                BrandColors.EMPTY, false, false, BrandAssets.EMPTY);
        JsonNode manifest = parse(WorkforceManifest.render(brand, Optional.empty()));
        assertEquals("Say \"Hi\" Workforce", manifest.get("name").asText());
        assertEquals("Say \"Hi\"", manifest.get("short_name").asText());
        assertTrue(manifest.get("icons") == null);
    }

    @Test
    @DisplayName("sizes reads a PNG header and falls back to any for anything else")
    void sizes() {
        assertEquals("192x180", WorkforceManifest.sizes(new BrandAsset(BrandAssetSlot.ICON, "a", "image/png", pngOf(192, 180))));
        assertEquals("any", WorkforceManifest.sizes(new BrandAsset(BrandAssetSlot.ICON, "b", "image/webp", new byte[40])));
        assertEquals("any", WorkforceManifest.sizes(new BrandAsset(BrandAssetSlot.ICON, "c", "image/png", new byte[4])));
    }
}
