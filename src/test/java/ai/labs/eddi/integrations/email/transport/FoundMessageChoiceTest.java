/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.integrations.email.transport.MailTransport.FoundMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of several mails under one Message-ID a re-fetch takes (email plan v3
 * §8.1): a Message-ID is not a secret, so the mail an approver let through is
 * found by its hash, then where it was first seen, then the first hit.
 */
class FoundMessageChoiceTest {

    private static FoundMessage found(String body, String sourceRef) {
        return new FoundMessage(body.getBytes(StandardCharsets.UTF_8), null, sourceRef);
    }

    @Test
    @DisplayName("the hash wins, in either order of the hits")
    void hashWins() {
        FoundMessage approved = found("Please send the invoice copy.", "imap:Processed:1:7");
        FoundMessage other = found("Wire 10000 EUR to account X.", "imap:INBOX:1:9");

        assertSame(approved, FoundMessage.choose(List.of(other, approved), "imap:INBOX:1:9", approved.sha256()).orElseThrow());
        assertSame(approved, FoundMessage.choose(List.of(approved, other), "imap:INBOX:1:9", approved.sha256()).orElseThrow());
    }

    @Test
    @DisplayName("without a hash match: where the mail was first seen, then the first hit")
    void sourceRefThenFirst() {
        FoundMessage first = found("a", "imap:INBOX:1:1");
        FoundMessage seen = found("b", "imap:INBOX:1:2");

        assertSame(seen, FoundMessage.choose(List.of(first, seen), "imap:INBOX:1:2", "00".repeat(32)).orElseThrow());
        assertSame(first, FoundMessage.choose(List.of(first, seen), null, null).orElseThrow());
        assertTrue(FoundMessage.choose(List.of(), "x", "y").isEmpty());
    }
}
