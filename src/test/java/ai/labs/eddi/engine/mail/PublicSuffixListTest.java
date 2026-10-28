/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicSuffixListTest {

    @Nested
    @DisplayName("the bundled file")
    class Bundled {

        @Test
        @DisplayName("is on the class path, not empty, and parses to thousands of rules")
        void present() throws Exception {
            try (InputStream in = PublicSuffixList.class.getResourceAsStream(PublicSuffixList.RESOURCE)) {
                assertNotNull(in, "the Public Suffix List is missing: DMARC alignment would fail for every message");
                byte[] bytes = in.readAllBytes();
                assertTrue(bytes.length > 100_000, "the bundled list is suspiciously small: " + bytes.length + " bytes");
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(text.contains("===BEGIN ICANN DOMAINS==="));
                assertTrue(text.contains("===END PRIVATE DOMAINS==="));
            }
            assertTrue(PublicSuffixList.bundled().size() > 5000, "rules: " + PublicSuffixList.bundled().size());
        }

        @Test
        @DisplayName("matches the SHA-256 its provenance file records (refresh both with scripts/update-public-suffix-list.py)")
        void provenance() throws Exception {
            Properties provenance = new Properties();
            try (InputStream in = PublicSuffixList.class.getResourceAsStream("/mail/public_suffix_list.properties")) {
                assertNotNull(in, "public_suffix_list.properties is missing");
                provenance.load(in);
            }
            byte[] bytes;
            try (InputStream in = PublicSuffixList.class.getResourceAsStream(PublicSuffixList.RESOURCE)) {
                bytes = in.readAllBytes();
            }
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(provenance.getProperty("sha256"), actual,
                    "public_suffix_list.dat differs from the upstream file its provenance names; it was edited or its line endings changed");
            assertEquals("MPL-2.0", provenance.getProperty("license"));
            assertTrue(provenance.getProperty("commit").matches("[0-9a-f]{40}"));
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"example.com, example.com", "mail.example.com, example.com", "a.b.c.example.com, example.com",
            "example.co.uk, example.co.uk", "mail.example.co.uk, example.co.uk", "co.uk, co.uk", "uk, uk",
            // A wildcard (*.ck) with an exception (!www.ck).
            "a.b.ck, a.b.ck", "www.ck, www.ck", "x.www.ck, www.ck",
            // *.kawasaki.jp with !city.kawasaki.jp.
            "city.kawasaki.jp, city.kawasaki.jp", "a.city.kawasaki.jp, city.kawasaki.jp", "foo.bar.kawasaki.jp, foo.bar.kawasaki.jp",
            // Private section: every github.io user is an organisation of its own.
            "alice.github.io, alice.github.io", "www.alice.github.io, alice.github.io",
            // Unknown TLD: the implicit "*" rule.
            "example.unknowntld, example.unknowntld", "a.example.unknowntld, example.unknowntld",
            // A Unicode rule (公司.cn) in its ASCII form.
            "shop.example.xn--55qx5d.cn, example.xn--55qx5d.cn",
            // Case and a trailing dot do not matter.
            "Mail.Example.COM., example.com"})
    @DisplayName("organisational domains from the bundled list")
    void organisationalDomains(String domain, String expected) {
        assertEquals(expected, PublicSuffixList.bundled().organizationalDomain(domain));
    }

    @Test
    @DisplayName("public suffixes from the bundled list")
    void publicSuffixes() {
        PublicSuffixList list = PublicSuffixList.bundled();
        assertEquals("co.uk", list.publicSuffix("mail.example.co.uk"));
        assertEquals("b.ck", list.publicSuffix("a.b.ck"));
        assertEquals("ck", list.publicSuffix("www.ck"));
        assertEquals("kawasaki.jp", list.publicSuffix("city.kawasaki.jp"));
        assertEquals("github.io", list.publicSuffix("alice.github.io"));
    }

    @Test
    @DisplayName("an exception prevails over a longer normal rule, and the longest normal rule wins otherwise")
    void precedence() throws Exception {
        String rules = "// comment\n\nexample\n*.example\n!keep.example\na.b.c.example\nb.c.example  trailing text\n";
        PublicSuffixList list = PublicSuffixList.parse(new ByteArrayInputStream(rules.getBytes(StandardCharsets.UTF_8)));

        assertEquals(5, list.size());
        assertEquals("x.y.example", list.organizationalDomain("w.x.y.example"));
        assertEquals("keep.example", list.organizationalDomain("w.keep.example"));
        assertEquals("x.a.b.c.example", list.organizationalDomain("w.x.a.b.c.example"));
        assertEquals("z.b.c.example", list.organizationalDomain("z.b.c.example"));
    }

    @Test
    @DisplayName("names that are not domains have no organisational domain")
    void notDomains() {
        PublicSuffixList list = PublicSuffixList.bundled();
        assertNull(list.organizationalDomain(null));
        assertNull(list.organizationalDomain(""));
        assertNull(list.organizationalDomain("a..example.com"));
        assertNull(list.organizationalDomain(".example.com"));
        assertNull(list.organizationalDomain("x".repeat(300) + ".com"));
    }
}
