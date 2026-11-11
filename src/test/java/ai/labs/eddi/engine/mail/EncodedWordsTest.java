/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodedWordsTest {

    @Test
    @DisplayName("B and Q words decode; whitespace between words is dropped, around them kept")
    void decodes() {
        assertEquals("Grüße aus Köln", EncodedWords.decode("=?UTF-8?Q?Gr=C3=BC=C3=9Fe?= aus =?UTF-8?B?S8O2bG4=?="));
        assertEquals("ab", EncodedWords.decode("=?UTF-8?Q?a?=   =?UTF-8?Q?b?="));
    }

    @Test
    @DisplayName("a UTF-8 character split across two words is joined as bytes")
    void splitMultibyte() {
        // U+1F34E is F0 9F 8D 8E; the first word ends mid-character.
        assertEquals("🍎", EncodedWords.decode("=?UTF-8?B?8J+N?= =?UTF-8?B?jg==?="));
    }

    @Test
    @DisplayName("an unknown charset falls back instead of throwing")
    void unknownCharset() {
        assertEquals("café", EncodedWords.decode("=?x-no-such-charset?Q?caf=E9?="));
    }

    @Test
    @DisplayName("bad base64 keeps the word as written; RFC 2231 language suffix ignored")
    void lenient() {
        assertEquals("=?UTF-8?B?***?=", EncodedWords.decode("=?UTF-8?B?***?="));
        assertEquals("Hallo", EncodedWords.decode("=?UTF-8*de?Q?Hallo?="));
        assertNull(EncodedWords.decode(null));
        assertEquals("plain", EncodedWords.decode("plain"));
    }

    @Test
    @DisplayName("charsets: us-ascii carrying UTF-8, Latin-1 read as Windows-1252, unknown names flagged")
    void charsets() {
        byte[] utf8 = "naïve".getBytes(StandardCharsets.UTF_8);
        assertEquals("naïve", MailCharsets.decode(utf8, "us-ascii").text());

        byte[] windows1252 = {(byte) 0x93, 'q', (byte) 0x94};
        assertEquals("“q”", MailCharsets.decode(windows1252, "iso-8859-1").text());

        MailCharsets.Decoded unknown = MailCharsets.decode(utf8, "x-klingon");
        assertTrue(unknown.charsetUnknown());
        assertEquals("naïve", unknown.text());

        MailCharsets.Decoded known = MailCharsets.decode("Привет".getBytes(Charset.forName("KOI8-R")), "\"koi8-r\"");
        assertFalse(known.charsetUnknown());
        assertEquals("Привет", known.text());

        assertEquals("", MailCharsets.decode(new byte[0], "utf-8").text());
    }

    @Test
    @DisplayName("charset lookup by name and alias, any case, from a table built once")
    void lookupTable() {
        assertEquals(StandardCharsets.UTF_8, MailCharsets.lookup("UTF-8"));
        assertEquals(StandardCharsets.UTF_8, MailCharsets.lookup(" utf8 "));
        assertEquals(Charset.forName("windows-1252"), MailCharsets.lookup("CP1252"));
        assertNull(MailCharsets.lookup("x-no-such-charset"));
        assertNull(MailCharsets.lookup("not a ?legal* name"));
        assertNull(MailCharsets.lookup(" "));
    }

    @Test
    @DisplayName("thousands of words in thousands of distinct unknown charsets decode within a time budget")
    void manyUnknownCharsets() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            text.append("=?x-unknown-").append(i).append("?Q?a?= x ");
        }
        String decoded = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> EncodedWords.decode(text.toString()));
        assertTrue(decoded.startsWith("a x a x"), decoded.substring(0, 10));
    }

    @Test
    @DisplayName("a display name is capped before its encoded words are decoded")
    void displayNameCappedBeforeDecoding() {
        String name = "=?x-unknown?Q?a?= ".repeat(50_000);
        String cleaned = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AddressNormalizer.cleanDisplayName(name));
        assertTrue(cleaned.length() <= 256);
    }
}
