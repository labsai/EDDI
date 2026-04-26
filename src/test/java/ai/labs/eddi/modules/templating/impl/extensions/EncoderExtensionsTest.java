/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for encoder extension methods in {@link EddiTemplateExtensions}.
 * Replaces the old EncoderWrapperTest.
 */
class EncoderExtensionsTest {

    @Test
    void testBase64WithHelloWorld() {
        String plain = "Hello World";
        String expected = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64Empty() {
        String plain = "";
        String expected = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64NullInput() {
        assertThrows(NullPointerException.class, () -> EddiTemplateExtensions.base64(null));
    }

    @Test
    void testBase64UrlWithSpecialCharacters() {
        String plain = "Test+and/Check";
        String expected = Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64Url(plain);
        assertEquals(expected, result);

        assertFalse(result.contains("+"), "URL-safe Base64 should not contain '+'");
        assertFalse(result.contains("/"), "URL-safe Base64 should not contain '/'");
    }

    @Test
    void testBase64UrlEmpty() {
        String plain = "";
        String expected = Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64Url(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64UrlNullInput() {
        assertThrows(NullPointerException.class, () -> EddiTemplateExtensions.base64Url(null));
    }

    @Test
    void testBase64MimeWithHelloWorld() {
        String plain = "Hello World";
        String expected = Base64.getMimeEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64Mime(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64MimeEmpty() {
        String plain = "";
        String expected = Base64.getMimeEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64Mime(plain);
        assertEquals(expected, result);
    }

    @Test
    void testBase64MimeNullInput() {
        assertThrows(NullPointerException.class, () -> EddiTemplateExtensions.base64Mime(null));
    }

    @Test
    void testBase64MimeWithLongInput() {
        String plain = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(100);
        String expected = Base64.getMimeEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        String result = EddiTemplateExtensions.base64Mime(plain);
        assertEquals(expected, result);
        assertTrue(result.contains("\r\n") || result.contains("\n"), "MIME encoded string should contain line breaks");
    }
}
