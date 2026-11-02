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

    static Map<String, String> conversation() {
        Map<String, String> pc = minimal();
        pc.put("mode", "conversation");
        pc.put("smtpHost", "SMTP.office365.com");
        return pc;
    }

    private static Map<String, String> conversationWith(String key, String value) {
        Map<String, String> pc = conversation();
        pc.put(key, value);
        return pc;
    }

    @Nested
    @DisplayName("conversation mode")
    class ConversationMode {

        @Test
        @DisplayName("the minimal configuration validates and reads with the documented defaults")
        void defaults() {
            assertEquals(List.of(), EmailChannelSettings.validate(conversation()));
            var settings = EmailChannelSettings.from(conversation());
            assertEquals(EmailChannelSettings.Mode.CONVERSATION, settings.mode());
            assertTrue(settings.replies());
            assertEquals("smtp.office365.com", settings.smtpHost());
            assertEquals(587, settings.smtpPort());
            assertEquals("smtp://smtp.office365.com:587", settings.smtpOrigin());
            assertEquals(EmailChannelSettings.DEFAULT_REPLY_TEMPLATE, settings.replyTemplate());
            assertEquals(EmailChannelSettings.DEFAULT_AI_DISCLOSURE, settings.aiDisclosure());
            assertEquals(EmailChannelSettings.ThreadParticipants.ORIGINAL_SENDER_ONLY, settings.threadParticipants());
            assertEquals(5, settings.maxRepliesPerThreadPerHour());
            assertFalse(settings.honorReplyTo());
            assertNull(settings.fromName());
            assertTrue(settings.requireAuthentication());
            assertTrue(settings.correlateThreads(), "a conversation always keeps its thread");
            assertEquals(List.of(), EmailChannelSettings.warnings(settings));
        }

        @Test
        @DisplayName("465 names smtps://host as the origin; other ports are refused")
        void smtpPorts() {
            assertEquals("smtps://smtp.office365.com", EmailChannelSettings.from(conversationWith("smtpPort", "465")).smtpOrigin());
            assertTrue(refused(conversationWith("smtpPort", "25"), "587 (STARTTLS, required) or 465"));
            assertTrue(refused(conversationWith("smtpPort", "2525"), "smtpPort"));
            Map<String, String> noHost = conversation();
            noHost.remove("smtpHost");
            assertTrue(refused(noHost, "requires platformConfig.smtpHost"));
        }

        @Test
        @DisplayName("requireAuthentication cannot be switched off: false is refused, and even unvalidated it reads true")
        void authenticationForced() {
            assertTrue(refused(conversationWith("requireAuthentication", "false"), "cannot be false in conversation mode"));
            assertEquals(List.of(), EmailChannelSettings.validate(conversationWith("requireAuthentication", "true")));
            assertTrue(EmailChannelSettings.from(conversationWith("requireAuthentication", "false")).requireAuthentication(),
                    "a reader of the settings never sees false in conversation mode");
            assertFalse(EmailChannelSettings.from(with("requireAuthentication", "false")).requireAuthentication(), "trigger mode may");
        }

        @Test
        @DisplayName("trigger-mode keys with a value are refused")
        void triggerKeys() {
            assertTrue(refused(conversationWith("correlateThreads", "false"), "belongs to email trigger mode"));
            assertEquals(List.of(), EmailChannelSettings.validate(conversationWith("correlateThreads", "")));
        }

        @Test
        @DisplayName("an emptied aiDisclosure switches it off and warns; honorReplyTo warns")
        void warnings() {
            var noDisclosure = EmailChannelSettings.from(conversationWith("aiDisclosure", ""));
            assertEquals("", noDisclosure.aiDisclosure());
            assertEquals(List.of(EmailChannelSettings.WARNING_AI_DISCLOSURE_DISABLED), EmailChannelSettings.warnings(noDisclosure));
            var replyTo = EmailChannelSettings.from(conversationWith("honorReplyTo", "true"));
            assertTrue(replyTo.honorReplyTo());
            assertEquals(List.of(EmailChannelSettings.WARNING_HONOR_REPLY_TO), EmailChannelSettings.warnings(replyTo));
            assertEquals(List.of(), EmailChannelSettings.warnings(EmailChannelSettings.from(with("aiDisclosure", ""))),
                    "trigger mode has nothing to disclose");
        }

        @Test
        @DisplayName("lengths, header-safe fromName, enumerations and ranges")
        void limits() {
            assertTrue(refused(conversationWith("fromName", "Support\r\nBcc: x@evil.example"), "fromName"));
            assertTrue(refused(conversationWith("fromName", "x".repeat(101)), "fromName"));
            assertTrue(refused(conversationWith("replyTemplate", "x".repeat(4001)), "replyTemplate"));
            assertTrue(refused(conversationWith("aiDisclosure", "x".repeat(501)), "aiDisclosure"));
            assertTrue(refused(conversationWith("threadParticipants", "everyone"), "threadParticipants"));
            assertTrue(refused(conversationWith("maxRepliesPerThreadPerHour", "0"), "maxRepliesPerThreadPerHour"));
            assertTrue(refused(conversationWith("honorReplyTo", "maybe"), "honorReplyTo"));
            assertEquals(EmailChannelSettings.ThreadParticipants.ANY_AUTHENTICATED_PARTICIPANT,
                    EmailChannelSettings.from(conversationWith("threadParticipants", "anyAuthenticatedParticipant")).threadParticipants());
            assertEquals("Contoso Support", EmailChannelSettings.from(conversationWith("fromName", "Contoso Support")).fromName());
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("conversation-mode keys with a value are refused in trigger mode; blank ones are ignored")
        void conversationKeysInTriggerMode() {
            assertTrue(refused(with("replyTemplate", "{output}"), "conversation mode"));
            assertTrue(refused(with("aiDisclosure", "x"), "conversation mode"));
            assertTrue(refused(with("smtpHost", "smtp.office365.com"), "conversation mode"));
            assertTrue(refused(with("honorReplyTo", "true"), "conversation mode"));
            assertEquals(List.of(), EmailChannelSettings.validate(with("aiDisclosure", "")));
            assertFalse(EmailChannelSettings.from(minimal()).replies(), "trigger mode never replies");
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
