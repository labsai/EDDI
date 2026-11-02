/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailChannelSettingsTest {

    static Map<String, String> minimal() {
        Map<String, String> pc = new HashMap<>();
        pc.put("mode", "trigger");
        pc.put("transport", "imap");
        pc.put("connection", "exo-invoices");
        pc.put("host", "outlook.office365.com");
        pc.put("address", "Invoices@Contoso.com");
        return pc;
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> pc = minimal();
        pc.put(key, value);
        return pc;
    }

    private static boolean refused(Map<String, String> pc, String fragment) {
        return EmailChannelSettings.validate(pc).stream().anyMatch(e -> e.contains(fragment));
    }

    @Test
    @DisplayName("the minimal trigger configuration validates and reads with the documented defaults")
    void defaults() {
        assertEquals(List.of(), EmailChannelSettings.validate(minimal()));
        var settings = EmailChannelSettings.from(minimal());
        assertEquals(EmailChannelSettings.Mode.TRIGGER, settings.mode());
        assertEquals("imap", settings.transport());
        assertEquals(993, settings.port());
        assertEquals("invoices@contoso.com", settings.address(), "normalised");
        assertEquals("INBOX", settings.folder());
        assertNull(settings.moveToFolder());
        assertEquals(60, settings.pollSeconds());
        assertTrue(settings.requireAuthentication());
        assertEquals(EmailChannelSettings.IdentityMode.PER_SENDER, settings.identityMode());
        assertFalse(settings.correlateThreads());
        assertEquals(EmailChannelSettings.Attachments.DROP, settings.attachments());
        assertEquals(25L * 1024 * 1024, settings.maxMessageBytes());
        assertTrue(settings.stripQuotedHistory());
        assertEquals(EmailChannelSettings.DEFAULT_INPUT_TEMPLATE, settings.inputTemplate());
        assertEquals(50, settings.maxTurnsPerSenderPerDay());
        assertNull(settings.dailyCostBudgetUsd());
        assertEquals("imaps://outlook.office365.com", settings.imapOrigin());
    }

    @Test
    @DisplayName("STARTTLS on 143 names imap://host:143 as the origin")
    void startTlsOrigin() {
        assertEquals("imap://outlook.office365.com:143", EmailChannelSettings.from(with("port", "143")).imapOrigin());
        assertTrue(refused(with("port", "25"), "993 (TLS) or 143"));
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("conversation mode is not supported yet, and its keys are refused in trigger mode")
        void conversationMode() {
            assertTrue(refused(with("mode", "conversation"), "not supported yet"));
            assertTrue(refused(with("replyTemplate", "{output}"), "conversation mode"));
            assertTrue(refused(with("aiDisclosure", "x"), "conversation mode"));
            assertTrue(refused(with("smtpHost", "smtp.office365.com"), "conversation mode"));
        }

        @Test
        @DisplayName("required keys")
        void required() {
            for (String key : List.of("mode", "transport", "connection", "host", "address")) {
                Map<String, String> pc = minimal();
                pc.remove(key);
                assertFalse(EmailChannelSettings.validate(pc).isEmpty(), key);
            }
            assertTrue(refused(with("transport", "graph"), "Supported: imap"));
        }

        @Test
        @DisplayName("unknown keys, including any credential-shaped key, are refused; blank ones are ignored")
        void unknownKeys() {
            assertTrue(refused(with("password", "hunter2"), "Unknown platformConfig key 'password'"));
            assertTrue(refused(with("requireAuth", "false"), "Unknown platformConfig key"));
            assertEquals(List.of(), EmailChannelSettings.validate(with("channelId", "")));
        }

        @Test
        @DisplayName("the connection is a name, never a credential")
        void connection() {
            assertEquals(List.of(), EmailChannelSettings.validate(with("connection", "${connection:exo-invoices}")));
            assertEquals("exo-invoices", EmailChannelSettings.from(with("connection", "${connection:exo-invoices}")).connection());
            assertTrue(refused(with("connection", "user:password@host"), "connection must be a connection name"));
            assertTrue(refused(with("connection", "${vault:imap-password}"), "connection must be a connection name"));
        }

        @Test
        @DisplayName("ranges, booleans and enumerations")
        void ranges() {
            assertTrue(refused(with("pollSeconds", "59"), "pollSeconds"));
            assertTrue(refused(with("pollSeconds", "abc"), "pollSeconds"));
            assertTrue(refused(with("maxMessageBytes", "100"), "maxMessageBytes"));
            assertTrue(refused(with("maxTurnsPerSenderPerDay", "-1"), "maxTurnsPerSenderPerDay"));
            assertTrue(refused(with("requireAuthentication", "yes"), "requireAuthentication"));
            assertTrue(refused(with("identityMode", "everyone"), "identityMode"));
            assertTrue(refused(with("attachments", "all"), "attachments"));
            assertTrue(refused(with("dailyCostBudgetUsd", "0"), "dailyCostBudgetUsd"));
            assertTrue(refused(with("dailyCostBudgetUsd", "lots"), "dailyCostBudgetUsd"));
            assertTrue(refused(with("inputTemplate", "x".repeat(4001)), "inputTemplate"));
        }

        @Test
        @DisplayName("processedAction is flag or move:<folder>, never a delete, never into the polled folder")
        void processedAction() {
            assertEquals("EDDI/Processed", EmailChannelSettings.from(with("processedAction", "move:EDDI/Processed")).moveToFolder());
            assertTrue(refused(with("processedAction", "delete"), "never deleted"));
            assertTrue(refused(with("processedAction", "move:"), "move:<folder>"));
            assertTrue(refused(with("processedAction", "move:INBOX"), "folder that is polled"));
        }

        @Test
        @DisplayName("sender patterns are user@domain, *@domain or *@*.domain")
        void senderPatterns() {
            assertEquals(List.of(), EmailChannelSettings.validate(with("senderAllowlist", "*@supplier-a.com, bob@supplier-b.de,*@*.example.org")));
            assertTrue(refused(with("senderAllowlist", "supplier-a.com"), "senderAllowlist entry 1"));
            assertTrue(refused(with("senderBlocklist", "*@*"), "senderBlocklist entry 1"));
        }

        @Test
        @DisplayName("store needs a MIME allowlist")
        void storeNeedsTypes() {
            assertTrue(refused(with("attachments", "store"), "requires platformConfig.attachmentMimeTypes"));
            Map<String, String> pc = with("attachments", "store");
            pc.put("attachmentMimeTypes", "application/pdf,image/*");
            assertEquals(List.of(), EmailChannelSettings.validate(pc));
            pc.put("attachmentMimeTypes", "*/*");
            assertTrue(refused(pc, "attachmentMimeTypes entries"));
        }

        @Test
        @DisplayName("owner notification addresses are plain addresses, at most five")
        void owners() {
            assertTrue(refused(with("ownerNotifyAddresses", "not-an-address"), "ownerNotifyAddresses entry 1"));
            assertTrue(refused(with("ownerNotifyAddresses", "a@x.com,b@x.com,c@x.com,d@x.com,e@x.com,f@x.com"), "at most 5"));
        }
    }

    @Test
    @DisplayName("the open-trigger warning: no allowlist and no authentication requirement")
    void openTriggerWarning() {
        assertEquals(List.of(), EmailChannelSettings.warnings(EmailChannelSettings.from(minimal())));
        Map<String, String> open = with("requireAuthentication", "false");
        assertEquals(List.of(EmailChannelSettings.WARNING_OPEN_TRIGGER), EmailChannelSettings.warnings(EmailChannelSettings.from(open)));
        open.put("senderAllowlist", "*@supplier.com");
        assertEquals(List.of(), EmailChannelSettings.warnings(EmailChannelSettings.from(open)));
    }
}
