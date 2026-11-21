/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.configs.channels.model.EmailSenderLists.Entries;
import ai.labs.eddi.configs.channels.model.EmailSenderLists.InvalidChange;
import ai.labs.eddi.configs.channels.model.EmailSenderLists.UpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EmailSenderLists — the targeted edit of an email integration's sender lists")
class EmailSenderListsTest {

    private static UpdateRequest request(List<String> addAllow, List<String> addBlock, List<String> removeAllow, List<String> removeBlock,
                                         Boolean confirmOpen) {
        return new UpdateRequest(1, new Entries(addAllow, addBlock), new Entries(removeAllow, removeBlock), null, confirmOpen);
    }

    @Test
    @DisplayName("entries are compared normalised; adds append, removes drop, no-ops are reported")
    void normalisedDiff() throws Exception {
        var change = EmailSenderLists.apply(List.of("Bob@Partner.com", "*@contoso.com"), List.of(),
                request(List.of("alice@PARTNER.com", "bob@partner.com"), List.of("spam@x.org"), List.of("*@CONTOSO.com", "nobody@x.org"),
                        List.of(), null));

        assertEquals(List.of("bob@partner.com", "alice@partner.com"), change.allow());
        assertEquals(List.of("spam@x.org"), change.block());
        assertEquals(List.of("allow:alice@partner.com", "block:spam@x.org"), change.added());
        assertEquals(List.of("allow:*@contoso.com"), change.removed());
        assertEquals(List.of("allow:nobody@x.org", "allow:bob@partner.com"), change.unchanged());
        assertTrue(change.changed());
        assertFalse(change.allowlistEmptied());
    }

    @Test
    @DisplayName("a request of only no-ops changes nothing")
    void onlyNoOps() throws Exception {
        var change = EmailSenderLists.apply(List.of("bob@partner.com"), List.of(), request(List.of("BOB@partner.com"), null, null, null, null));
        assertFalse(change.changed());
    }

    @Test
    @DisplayName("an invalid pattern, *@* included, is refused")
    void invalidPattern() {
        var error = assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(List.of(), List.of(),
                request(List.of("*@*", "not-an-address"), null, null, null, null)));
        assertEquals(2, error.errors().size());
    }

    @Test
    @DisplayName("an invalid entry already stored can be removed verbatim, and is still refused as an addition")
    void removesStoredInvalidEntry() throws Exception {
        var change = EmailSenderLists.apply(List.of("Not-An-Address", "bob@partner.com"), List.of(),
                request(List.of("bob@partner.com"), null, List.of("not-an-address"), null, null));
        assertEquals(List.of("bob@partner.com"), change.allow());
        assertEquals(List.of("allow:not-an-address"), change.removed());
        assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(List.of("not-an-address"), List.of(),
                request(List.of("not-an-address"), null, null, null, null)));
        assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(List.of("bob@partner.com"), List.of(),
                request(null, null, List.of("never-stored"), null, null)));
    }

    @Test
    @DisplayName("an entry on both lists, or both added and removed, is refused")
    void bothLists() {
        assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(List.of(), List.of("alice@partner.com"),
                request(List.of("Alice@partner.com"), null, null, null, null)));
        assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(List.of("alice@partner.com"), List.of(),
                request(List.of("x@partner.com"), null, List.of("x@partner.com"), null, null)));
    }

    @Test
    @DisplayName("more than 200 entries are refused")
    void cap() {
        List<String> current = new ArrayList<>();
        for (int i = 0; i < EmailChannelSettings.MAX_LIST_ENTRIES; i++) {
            current.add("user" + i + "@partner.com");
        }
        assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(current, List.of(),
                request(List.of("one-more@partner.com"), null, null, null, null)));
    }

    @Test
    @DisplayName("emptying a non-empty allowlist needs confirmOpen; an allowlist that was empty is not 'emptied'")
    void emptyingNeedsConfirmation() throws Exception {
        var remove = request(null, null, List.of("bob@partner.com"), null, null);
        var refused = assertThrows(InvalidChange.class, () -> EmailSenderLists.apply(List.of("bob@partner.com"), List.of(), remove));
        assertTrue(refused.getMessage().contains("confirmOpen"));

        var confirmed = EmailSenderLists.apply(List.of("bob@partner.com"), List.of(),
                request(null, null, List.of("bob@partner.com"), null, true));
        assertTrue(confirmed.allowlistEmptied());
        assertTrue(confirmed.allow().isEmpty());

        var neverAllowlisted = EmailSenderLists.apply(List.of(), List.of(), request(null, List.of("spam@x.org"), null, null, null));
        assertFalse(neverAllowlisted.allowlistEmptied());
    }

    @Test
    @DisplayName("the lists are written back comma-separated, and an empty list removes its key")
    void writesBack() {
        var pc = EmailSenderLists.withLists(Map.of("senderAllowlist", "a@b.com", "mode", "trigger"), List.of(), List.of("x@y.com", "*@z.com"));
        assertFalse(pc.containsKey("senderAllowlist"));
        assertEquals("x@y.com,*@z.com", pc.get("senderBlocklist"));
        assertEquals("trigger", pc.get("mode"));
    }

    @Test
    @DisplayName("the reason is trimmed, stripped of control characters and capped")
    void reason() {
        assertNull(EmailSenderLists.cleanReason("   "));
        assertEquals("a b", EmailSenderLists.cleanReason(" a\nb "));
        assertEquals(EmailSenderLists.MAX_REASON_LENGTH, EmailSenderLists.cleanReason("x".repeat(2000)).length());
    }

    @Test
    @DisplayName("subdomain and domain patterns keep their form, domains normalised")
    void patterns() {
        assertEquals("*@*.partner.com", EmailSenderLists.normalise(" *@*.PARTNER.com "));
        assertEquals("*@partner.com", EmailSenderLists.normalise("*@Partner.COM"));
        assertThrows(IllegalArgumentException.class, () -> EmailSenderLists.normalise("*@*"));
    }
}
