/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default EDDI favicon is kept as several copies, one per surface that
 * serves it. In 6.7.0 one of them (the Chat UI's, unchanged since 2024) still
 * carried the old logo, and it was the one the favicon slot's default pointed
 * at, so every unbranded install showed the old icon in the browser tab and a
 * cropped old logo on Admin → Branding. Every copy must be the same file.
 */
class DefaultFaviconConsistencyTest {

    /**
     * The reference: the icon the Manager ships and the design files derive from.
     */
    private static final Path REFERENCE = Path.of("ui", "manager", "public", "eddi-icon.ico");

    private static final List<Path> COPIES = List.of(
            Path.of("src", "main", "resources", "META-INF", "branding", "favicon.ico"),
            Path.of("ui", "chat", "public", "img", "favicon.ico"),
            Path.of("keycloak", "themes", "eddi", "login", "resources", "img", "favicon.ico"),
            Path.of("docs-site", "static", "favicon.ico"));

    @Test
    void everyBundledCopyOfTheDefaultFaviconIsTheCurrentIcon() throws IOException, NoSuchAlgorithmException {
        assertTrue(Files.isRegularFile(REFERENCE), "missing " + REFERENCE.toAbsolutePath());
        String expected = sha256(Files.readAllBytes(REFERENCE));

        for (Path copy : COPIES) {
            assertTrue(Files.isRegularFile(copy), "missing " + copy.toAbsolutePath());
            assertEquals(expected, sha256(Files.readAllBytes(copy)),
                    copy + " is not the current EDDI icon (" + REFERENCE + "); copy the reference over it");
        }
    }

    @Test
    void theFaviconSlotDefaultIsOnTheClasspathAndIsTheCurrentIcon() throws IOException {
        // Not a UI build output: a build with -DskipUi must still serve a default.
        String resource = BrandAssetSlot.FAVICON.bundledResource();
        assertNotNull(resource, "the favicon slot must have a bundled default");

        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the classpath");
            assertArrayEquals(Files.readAllBytes(REFERENCE), in.readAllBytes(),
                    resource + " is not the current EDDI icon (" + REFERENCE + ")");
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
