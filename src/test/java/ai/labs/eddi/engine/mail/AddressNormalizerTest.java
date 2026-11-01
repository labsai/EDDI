/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressNormalizerTest {

    @Test
    @DisplayName("lower-cases the whole address")
    void lowerCases() {
        assertEquals("alice.example@example.com", AddressNormalizer.normalize("Alice.Example@EXAMPLE.com"));
    }

    @Test
    @DisplayName("converts an IDN domain to ASCII")
    void idn() {
        assertEquals("info@xn--bcher-kva.example", AddressNormalizer.normalize("info@bücher.example"));
        assertEquals("info@xn--bcher-kva.example", AddressNormalizer.normalize("INFO@XN--BCHER-KVA.example"));
    }

    @Test
    @DisplayName("does not fold dots or +tags")
    void noFolding() {
        assertEquals("first.last+billing@example.com", AddressNormalizer.normalize("first.last+billing@example.com"));
        assertFalse(AddressNormalizer.normalize("firstlast@example.com").equals(AddressNormalizer.normalize("first.last@example.com")));
    }

    @Test
    @DisplayName("keeps a quoted local part and a trailing domain dot is dropped")
    void quotedAndTrailingDot() {
        assertEquals("\"john doe\"@example.com", AddressNormalizer.normalize("\"John Doe\"@example.com."));
    }

    @Test
    @DisplayName("accepts a non-ASCII local part (RFC 6532)")
    void utf8LocalPart() {
        assertEquals("jürgen@example.de", AddressNormalizer.normalize("Jürgen@example.de"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "no-at-sign", "@example.com", "user@", "user@[192.0.2.1]", "user@localhost", "user@192.0.2.1",
            "us er@example.com", "user@exa mple.com", "user@-example.com", "user@example..com", "a..b@example.com", ".a@example.com",
            "user\r\n@example.com", "user@example.com\nBcc: x@example.org", "\"unbalanced@example.com", "user@exam_ple.com"})
    @DisplayName("refuses what is not an addr-spec")
    void refusesInvalid(String input) {
        assertThrows(IllegalArgumentException.class, () -> AddressNormalizer.normalize(input));
        assertTrue(AddressNormalizer.tryNormalize(input).isEmpty());
    }

    @Test
    @DisplayName("refuses an over-long local part and address")
    void refusesTooLong() {
        assertThrows(IllegalArgumentException.class, () -> AddressNormalizer.normalize("a".repeat(65) + "@example.com"));
        assertThrows(IllegalArgumentException.class, () -> AddressNormalizer.normalize("a@" + "b".repeat(250) + ".com"));
    }

    @Test
    @DisplayName("a refusal never echoes the input")
    void refusalIsNotPersonalData() {
        var error = assertThrows(IllegalArgumentException.class, () -> AddressNormalizer.normalize("secret.person@[192.0.2.1]"));

        assertFalse(error.getMessage().contains("secret.person"));
    }

    @Test
    @DisplayName("parses display names, including encoded ones, and normalises the address")
    void parsesDisplayNames() {
        var parsed = AddressNormalizer.parseList("\"Builder, Bob\" <Bob@Example.com>, =?UTF-8?Q?J=C3=BCrgen?= <j@example.de>, plain@example.org");

        assertEquals(0, parsed.invalid());
        assertEquals(List.of("bob@example.com", "j@example.de", "plain@example.org"), parsed.addresses().stream().map(MailAddress::address).toList());
        assertEquals("Builder, Bob", parsed.addresses().get(0).displayName());
        assertEquals("Jürgen", parsed.addresses().get(1).displayName());
        assertNull(parsed.addresses().get(2).displayName());
    }

    @Test
    @DisplayName("a display name cannot carry a line break")
    void displayNameControls() {
        assertEquals("Evil Bcc: x", AddressNormalizer.cleanDisplayName("Evil\r\nBcc: x"));
    }

    @Test
    @DisplayName("a display name drops bidi embeddings, overrides and isolates")
    void displayNameBidi() {
        char rlo = (char) 0x202E;
        char lri = (char) 0x2066;
        char pdi = (char) 0x2069;
        assertEquals("Support gpj.exe", AddressNormalizer.cleanDisplayName("Support " + rlo + "gpj.exe"));
        assertEquals("Alice", AddressNormalizer.cleanDisplayName(lri + "Alice" + pdi));
        assertNull(AddressNormalizer.cleanDisplayName(String.valueOf(rlo)));
    }

    @Test
    @DisplayName("groups are flattened and invalid members counted")
    void groups() {
        var parsed = AddressNormalizer.parseList("Team: a@example.com, b@example.org;, undisclosed-recipients:;");

        assertEquals(List.of("a@example.com", "b@example.org"), parsed.addresses().stream().map(MailAddress::address).toList());
    }

    @Test
    @DisplayName("parseSingle refuses zero, several, or invalid mailboxes")
    void parseSingle() {
        assertEquals("a@example.com", AddressNormalizer.parseSingle("A <a@example.com>").orElseThrow().address());
        assertTrue(AddressNormalizer.parseSingle("a@example.com, b@example.com").isEmpty());
        assertTrue(AddressNormalizer.parseSingle("not an address").isEmpty());
        assertTrue(AddressNormalizer.parseSingle(null).isEmpty());
    }

    @Test
    @DisplayName("MailAddress prints its domain only")
    void mailAddressToString() {
        assertEquals("MailAddress[domain=example.com]", new MailAddress("alice@example.com", "Alice").toString());
        assertEquals("alice", new MailAddress("alice@example.com", " ").localPart());
        assertNull(new MailAddress("alice@example.com", " ").displayName());
    }
}
