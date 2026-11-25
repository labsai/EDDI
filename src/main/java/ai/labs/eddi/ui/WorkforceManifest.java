/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The web app manifest that makes Workforce installable (frontend unification
 * plan, T1.11), in the deployment's brand: its product name, its primary colour
 * as the theme colour, and its icon slot ({@code /branding/icon}, the bundled
 * EDDI icon when the brand has none). Rendered per request from the current
 * brand, so a rebrand shows in the next install without a rebuild.
 * <p>
 * No service worker goes with it: browsers no longer require one to install,
 * and Workforce shows signed-in data that must never be served from a cache.
 */
public final class WorkforceManifest {

    /** Where the app starts and what it covers: every Workforce route. */
    static final String START_URL = "/workforce";
    static final String ICON_URL = "/branding/icon";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private WorkforceManifest() {
    }

    /** The manifest as JSON, for the brand and its icon (if any). */
    public static String render(ResolvedBrand brand, Optional<BrandAsset> icon) {
        String themeColor = brand.colors().primary() != null ? brand.colors().primary() : BrandedShell.WORKFORCE.defaultThemeColor();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", START_URL);
        manifest.put("name", brand.productName() + " Workforce");
        manifest.put("short_name", brand.shortName() != null ? brand.shortName() : brand.productName());
        manifest.put("description", brand.productName() + " Workforce — chats and teams of AI agents");
        manifest.put("start_url", START_URL);
        manifest.put("scope", START_URL);
        manifest.put("display", "standalone");
        manifest.put("background_color", BrandedShell.WORKFORCE.defaultThemeColor());
        manifest.put("theme_color", themeColor);
        icon.ifPresent(asset -> manifest.put("icons", List.of(iconEntry(asset))));
        try {
            return MAPPER.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> iconEntry(BrandAsset asset) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("src", ICON_URL);
        entry.put("type", asset.contentType());
        entry.put("sizes", sizes(asset));
        entry.put("purpose", "any");
        return entry;
    }

    /**
     * {@code WxH} for a PNG (read from its header), {@code any} otherwise: an SVG
     * scales to any size, and for other raster types the browser measures the image
     * itself.
     */
    static String sizes(BrandAsset asset) {
        byte[] data = asset.data();
        if (data == null || data.length < 24) {
            return "any";
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return "any";
            }
        }
        // IHDR is the first chunk: width and height, big-endian, at 16 and 20.
        long width = readInt(data, 16);
        long height = readInt(data, 20);
        return width > 0 && height > 0 ? width + "x" + height : "any";
    }

    private static long readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24) | ((data[offset + 1] & 0xFFL) << 16) | ((data[offset + 2] & 0xFFL) << 8) | (data[offset + 3] & 0xFFL);
    }
}
