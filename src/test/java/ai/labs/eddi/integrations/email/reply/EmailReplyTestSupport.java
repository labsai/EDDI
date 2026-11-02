/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixtures for the conversation-mode reply tests. */
final class EmailReplyTestSupport {

    static final String INTEGRATION = "a1b2c3d4e5f60718293a4b5c";
    static final String CONVERSATION = "c0ffee00c0ffee00c0ffee00";
    static final String MAILBOX = "support@contoso.com";
    static final String SENDER = "alice@supplier.com";
    static final String MESSAGE_ID = "<q1@supplier.com>";
    static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private EmailReplyTestSupport() {
    }

    static Map<String, String> conversationConfig(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>();
        pc.put("mode", "conversation");
        pc.put("transport", "imap");
        pc.put("connection", "mailbox");
        pc.put("host", "imap.contoso.com");
        pc.put("smtpHost", "smtp.contoso.com");
        pc.put("address", MAILBOX);
        pc.putAll(extra);
        return pc;
    }

    static EmailChannelSettings settings(Map<String, String> extra) {
        return EmailChannelSettings.from(conversationConfig(extra));
    }

    static EmailChannelSettings settings() {
        return settings(Map.of());
    }

    static LedgerEntry inbound(State state) {
        return inbound("in-1", MESSAGE_ID, SENDER, state);
    }

    static LedgerEntry inbound(String id, String messageId, String sender, State state) {
        return new LedgerEntry(id, INTEGRATION, Kind.TRIGGER, Direction.IN, messageId, CONVERSATION, "imap:INBOX:1:1", "in:" + id, sender, state, 1,
                null, null, null, null, NOW.minusSeconds(60), NOW, NOW.plus(Duration.ofDays(30)));
    }

    static Map<String, Object> mail(String messageId, String from) {
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("from", from);
        mail.put("fromName", "Alice");
        mail.put("subject", "Order 4711");
        mail.put("messageId", messageId);
        mail.put("inReplyTo", null);
        mail.put("references", List.of("<root@supplier.com>"));
        mail.put("replyTo", List.of());
        mail.put("authentication", "aligned-pass");
        return mail;
    }

    static Map<String, Object> mail() {
        return mail(MESSAGE_ID, SENDER);
    }

    static ConversationOutput output(String text, Map<String, Object> mail) {
        ConversationOutput output = new ConversationOutput();
        if (text != null) {
            output.put("output", List.of(text));
        }
        if (mail != null) {
            output.put("context", new HashMap<>(Map.of("mail", mail)));
        }
        return output;
    }

    /** A conversation: the start step's greeting, then the mail's step. */
    static SimpleConversationMemorySnapshot snapshot(ConversationState state, String userId, ConversationOutput... outputs) {
        SimpleConversationMemorySnapshot snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION);
        snapshot.setConversationState(state);
        snapshot.setUserId(userId);
        snapshot.getConversationOutputs().addAll(List.of(outputs));
        return snapshot;
    }

    static SimpleConversationMemorySnapshot answered(String answer) {
        return snapshot(ConversationState.READY, EmailUserIdentity.perSender(INTEGRATION, SENDER), output("Hello, I am the order desk.", null),
                output(answer, mail()));
    }
}
