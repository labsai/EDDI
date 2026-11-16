/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl.extensions;

import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void mimeHeaderShortValueIsOneEncodedWord() throws Exception {
        String result = EddiTemplateExtensions.mimeHeader("Hello World");

        assertEquals("=?UTF-8?B?SGVsbG8gV29ybGQ=?=", result);
        assertEquals("Hello World", MimeUtility.decodeText(result));
    }

    @Test
    void mimeHeaderEmptyIsEmpty() {
        assertEquals("", EddiTemplateExtensions.mimeHeader(""));
    }

    @Test
    void mimeHeaderNullInput() {
        assertThrows(NullPointerException.class, () -> EddiTemplateExtensions.mimeHeader(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"éééééééééééééééééééééééé", "Ünïcödé Sübjéct – with a dash, emoji 😀😀😀 and more text to fold",
            "€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€",
            "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"})
    void mimeHeaderFoldsLongValuesIntoValidWords(String subject) throws Exception {
        String result = EddiTemplateExtensions.mimeHeader(subject);

        String[] lines = result.split("\r\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                assertEquals(' ', lines[i].charAt(0), "continuation line must start with a space");
            }
            String word = i == 0 ? lines[i] : lines[i].substring(1);
            assertTrue(word.startsWith("=?UTF-8?B?") && word.endsWith("?="), "not an encoded word: " + word);
            assertTrue(word.length() <= 75, "RFC 2047 caps an encoded word at 75 characters: " + word.length());
            assertTrue(("Subject: " + word).length() <= 76, "header line too long");
            // each word decodes on its own: no multi-byte character is split across two
            // words
            String payload = word.substring("=?UTF-8?B?".length(), word.length() - 2);
            String decoded = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            assertFalse(decoded.contains("�"), "word cuts a character in half");
        }
        assertTrue(lines.length > 1 || subject.getBytes(StandardCharsets.UTF_8).length <= 39);
        assertEquals(subject, MimeUtility.decodeText(MimeUtility.unfold(result)));
    }

    @Test
    void mimeHeaderEncodesAsciiLineBreaksSoTheyCannotEndTheHeader() throws Exception {
        String injection = "Hi\r\nBcc: attacker@example.com";

        String result = EddiTemplateExtensions.mimeHeader(injection);

        String unfolded = result.replace("\r\n ", "");
        assertFalse(unfolded.contains("\r"), "raw CR outside a fold");
        assertFalse(unfolded.contains("\n"), "raw LF outside a fold");
        assertFalse(result.contains("Bcc"), "header text leaked unencoded");
        assertEquals(injection, MimeUtility.decodeText(MimeUtility.unfold(result)));
    }
}
