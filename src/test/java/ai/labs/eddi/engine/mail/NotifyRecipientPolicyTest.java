/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The operator's recipient-domain allowlist for notification mail. */
class NotifyRecipientPolicyTest {

    @Test
    @DisplayName("unset: the sender's domain and its subdomains, nothing else")
    void defaultIsSenderDomain() {
        var policy = NotifyRecipientPolicy.of(null, "eddi-notifications@Example.com");

        assertTrue(policy.allows("ops@example.com"));
        assertTrue(policy.allows("ops@eu.example.com"));
        assertFalse(policy.allows("ops@example.com.evil.test"));
        assertFalse(policy.allows("ops@notexample.com"));
        assertFalse(policy.allows("victim@gmail.com"));
    }

    @Test
    @DisplayName("without entries and without a sender nothing is allowed")
    void nothingWithoutSender() {
        assertFalse(NotifyRecipientPolicy.of(List.of(" "), null).allows("ops@example.com"));
    }

    @Test
    @DisplayName("exact domains match only themselves, *.domain only subdomains, * everything")
    void entries() {
        var policy = NotifyRecipientPolicy.of(List.of("partner.test", "*.corp.example"), "eddi@example.com");

        assertTrue(policy.allows("a@partner.test"));
        assertFalse(policy.allows("a@sub.partner.test"));
        assertTrue(policy.allows("a@eu.corp.example"));
        assertFalse(policy.allows("a@corp.example"));
        assertFalse(policy.allows("a@example.com"), "configured entries replace the default");
        assertTrue(NotifyRecipientPolicy.of(List.of("*"), null).allows("anyone@anywhere.test"));
    }

    @Test
    @DisplayName("an entry that is not a domain is refused rather than ignored")
    void invalidEntry() {
        assertThrows(IllegalArgumentException.class, () -> NotifyRecipientPolicy.of(List.of("not a domain"), null));
        assertThrows(IllegalArgumentException.class, () -> NotifyRecipientPolicy.of(List.of("*@example.com"), null));
    }
}
