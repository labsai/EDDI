/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.transport.MailTransport.MarkResult;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Marking against a server that takes no custom keywords (Exchange Online, some
 * hosters): GreenMail cannot be made to refuse them, so the folder is a mock.
 */
class ImapMarkProcessedTest {

    private IMAPStore store;
    private IMAPFolder folder;
    private ImapMailTransport.ImapMessage message;
    private String integrationId;

    @BeforeEach
    void setUp() {
        store = mock(IMAPStore.class);
        folder = mock(IMAPFolder.class);
        message = new ImapMailTransport.ImapMessage(mock(IMAPMessage.class), 7, 1, "INBOX");
        integrationId = "int-" + UUID.randomUUID();
    }

    private static EmailChannelSettings settings(String processedAction) {
        Map<String, String> pc = new HashMap<>(Map.of("mode", "trigger", "transport", "imap", "connection", "c", "host", "imap.x.com", "address",
                "a@x.com"));
        if (processedAction != null) {
            pc.put("processedAction", processedAction);
        }
        return EmailChannelSettings.from(pc);
    }

    private MarkResult mark(EmailChannelSettings settings) throws Exception {
        return new ImapMailTransport.ImapSession(integrationId, store, folder).markProcessed(message, settings);
    }

    @Test
    @DisplayName("PERMANENTFLAGS without \\* or the keyword: no STORE, no failure, one WARN per integration")
    void noKeywordsDegrades() throws Exception {
        when(folder.getPermanentFlags()).thenReturn(new Flags(Flags.Flag.SEEN));

        assertEquals(MarkResult.NOTHING, mark(settings(null)));
        assertEquals(MarkResult.NOTHING, mark(settings(null)));

        verify(folder, never()).setFlags(any(Message[].class), any(Flags.class), anyBoolean());
        assertTrue(ImapMailTransport.WARNED_NO_KEYWORDS.contains(integrationId), "warned once for this integration");
    }

    @Test
    @DisplayName("without keywords, move: still moves the mail out of the folder")
    void noKeywordsStillMoves() throws Exception {
        when(folder.getPermanentFlags()).thenReturn(new Flags(Flags.Flag.SEEN));
        when(store.hasCapability("MOVE")).thenReturn(true);
        Folder destination = mock(Folder.class);
        when(destination.exists()).thenReturn(true);
        when(store.getFolder("Processed")).thenReturn(destination);

        assertEquals(MarkResult.MOVED, mark(settings("move:Processed")));
        verify(folder).moveMessages(any(Message[].class), eq(destination));
        verify(folder, never()).setFlags(any(Message[].class), any(Flags.class), anyBoolean());
    }

    @Test
    @DisplayName("the keyword named explicitly in PERMANENTFLAGS is enough")
    void explicitKeyword() throws Exception {
        Flags permanent = new Flags(Flags.Flag.SEEN);
        permanent.add(MailTransport.PROCESSED_KEYWORD);
        when(folder.getPermanentFlags()).thenReturn(permanent);

        assertEquals(MarkResult.FLAGGED, mark(settings(null)));
        verify(folder).setFlags(any(Message[].class), eq(new Flags(MailTransport.PROCESSED_KEYWORD)), eq(true));
    }

    @Test
    @DisplayName("a server that advertises keywords but refuses the STORE degrades the same way")
    void refusedStoreDegrades() throws Exception {
        Flags permanent = new Flags();
        permanent.add(Flags.Flag.USER);
        when(folder.getPermanentFlags()).thenReturn(permanent);
        doThrow(new MessagingException("NO keywords not permitted")).when(folder).setFlags(any(Message[].class), any(Flags.class), anyBoolean());

        assertEquals(MarkResult.NOTHING, mark(settings(null)));
        assertTrue(ImapMailTransport.WARNED_NO_KEYWORDS.contains(integrationId));
    }

    @Test
    @DisplayName("keywordAllowed reads \\* and the explicit keyword only")
    void keywordAllowed() {
        Flags any = new Flags();
        any.add(Flags.Flag.USER);
        assertTrue(ImapMailTransport.keywordAllowed(any));
        assertFalse(ImapMailTransport.keywordAllowed(new Flags(Flags.Flag.SEEN)));
        assertFalse(ImapMailTransport.keywordAllowed(null));
    }
}
