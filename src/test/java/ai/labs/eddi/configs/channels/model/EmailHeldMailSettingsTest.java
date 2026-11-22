/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code emailControl.onRefused} and {@code emailControl.review} (email channel
 * v3 plan §7.1): defaults, validation and the Held folder.
 */
class EmailHeldMailSettingsTest {

    private static Map<String, String> trigger(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "exo", "host", "outlook.office365.com",
                "address", "invoices@contoso.com"));
        pc.putAll(extra);
        return pc;
    }

    private static EmailControl control(EmailControl.OnRefused onRefused, EmailControl.Review review) {
        return new EmailControl(null, onRefused, review);
    }

    @Test
    @DisplayName("defaults: drop everything, P7D, 50 a day, 5 per sender domain, no reviewers")
    void defaults() {
        EmailChannelSettings settings = EmailChannelSettings.from(trigger(Map.of()), null);
        assertFalse(settings.reviewsNotAllowlisted());
        assertFalse(settings.reviewsUnauthenticated());
        assertEquals(Duration.ofDays(7), settings.reviewExpiry());
        assertEquals(50, settings.maxHeldPerDay());
        assertEquals(5, settings.maxHeldPerSenderDomainPerDay());
        assertEquals(List.of(), settings.reviewers());
        assertNull(settings.heldFolder(), "flagged, not moved: held mail stays where it is");
    }

    @Test
    @DisplayName("set values are read; an unknown stored value reads as drop")
    void values() {
        EmailControl c = control(new EmailControl.OnRefused("REVIEW", "bogus", null),
                new EmailControl.Review("P2D", 10, 2, List.of(" role:mail-desk ", "", "alice")));
        EmailChannelSettings settings = EmailChannelSettings.from(trigger(Map.of()), c);
        assertTrue(settings.reviewsNotAllowlisted());
        assertFalse(settings.reviewsUnauthenticated(), "unknown reads as drop");
        assertEquals(Duration.ofDays(2), settings.reviewExpiry());
        assertEquals(10, settings.maxHeldPerDay());
        assertEquals(2, settings.maxHeldPerSenderDomainPerDay());
        assertEquals(List.of("role:mail-desk", "alice"), settings.reviewers());
    }

    @Test
    @DisplayName("validation: actions, notAddressed (pending), expiry bounds, caps and reviewers")
    void validation() {
        List<String> errors = EmailChannelSettings.validate(trigger(Map.of()), control(new EmailControl.OnRefused("hold", "review", "review"),
                new EmailControl.Review("P1Y", 0, 101, List.of("role:"))));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("emailControl.onRefused.notAllowlisted")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("emailControl.onRefused.notAddressed")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("emailControl.review.expiry")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("emailControl.review.maxHeldPerDay")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("emailControl.review.maxHeldPerSenderDomainPerDay")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("emailControl.review.reviewers")), errors.toString());
        assertEquals(List.of(), EmailChannelSettings.validate(trigger(Map.of()), control(new EmailControl.OnRefused("review", "drop", "drop"),
                new EmailControl.Review("PT12H", 1, 1, List.of("role:eddi-approver", "bob")))));
    }

    @Test
    @DisplayName("the Held folder is the move folder's sibling, with its separator")
    void heldFolder() {
        assertEquals("EDDI/Held", EmailChannelSettings.from(trigger(Map.of("processedAction", "move:EDDI/Processed")), null).heldFolder());
        assertEquals("INBOX.Held", EmailChannelSettings.from(trigger(Map.of("processedAction", "move:INBOX.Processed")), null).heldFolder());
        assertEquals("EDDI/Held", EmailChannelSettings.from(trigger(Map.of("processedAction", "move:Processed")), null).heldFolder());
    }

    @Test
    @DisplayName("copy and equality cover the new sections")
    void copy() {
        EmailControl c = control(new EmailControl.OnRefused("review", null, null), new EmailControl.Review("P3D", 5, 1, List.of("alice")));
        EmailControl copy = c.copy();
        assertEquals(c, copy);
        assertNotSame(c.getReview().getReviewers(), copy.getReview().getReviewers());
        copy.getOnRefused().setNotAllowlisted("drop");
        assertEquals("review", c.getOnRefused().getNotAllowlisted());
        assertFalse(c.equals(copy));
    }

    @Test
    @DisplayName("trigger mode without authentication warns that unauthenticated mail runs with every tool")
    void anonymousToolsWarning() {
        assertTrue(EmailChannelSettings.warnings(EmailChannelSettings.from(trigger(Map.of("requireAuthentication", "false")), null))
                .contains(EmailChannelSettings.WARNING_ANONYMOUS_TOOLS));
        assertFalse(EmailChannelSettings.warnings(EmailChannelSettings.from(trigger(Map.of()), null))
                .contains(EmailChannelSettings.WARNING_ANONYMOUS_TOOLS));
    }
}
