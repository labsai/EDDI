/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.INTEGRATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.MAILBOX;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.SENDER;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.inbound;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.mail;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reply recipient rules (email plan §9). Every rule is tested alone against
 * an otherwise allowed reply, so reverting any one of them fails a test here.
 */
class EmailReplyRecipientsTest {

    private static final String PER_SENDER = EmailUserIdentity.perSender(INTEGRATION, SENDER);

    private static EmailReplyRecipients.Decision decide(EmailChannelSettings settings, LedgerEntry inbound, Map<String, Object> mail, String userId) {
        return EmailReplyRecipients.decide(INTEGRATION, settings, inbound, mail, userId);
    }

    private static Map<String, Object> mailWith(String key, Object value) {
        Map<String, Object> mail = new HashMap<>(mail());
        mail.put(key, value);
        return mail;
    }

    @Test
    @DisplayName("the authenticated From of the answered mail, and nobody else")
    void allowed() {
        var decision = decide(settings(), inbound(State.TURN_SUBMITTED), mail(), PER_SENDER);
        assertTrue(decision.allowed(), decision.toString());
        assertEquals(SENDER, decision.recipient());
        assertTrue(decide(settings(), inbound(State.DONE), mail(), PER_SENDER).allowed(), "DONE too");
        assertTrue(decide(settings(Map.of("identityMode", "integration")), inbound(State.DONE), mail(), EmailUserIdentity.integration(INTEGRATION))
                .allowed(), "the shared identity of this integration");
    }

    @Test
    @DisplayName("never in trigger mode")
    void triggerMode() {
        var trigger = EmailChannelSettings.from(Map.of("mode", "trigger", "transport", "imap", "connection", "mailbox", "host", "imap.contoso.com",
                "address", MAILBOX));
        assertEquals("not_conversation_mode", decide(trigger, inbound(State.DONE), mail(), PER_SENDER).refusal());
        assertEquals("not_conversation_mode", decide(null, inbound(State.DONE), mail(), PER_SENDER).refusal());
    }

    @Test
    @DisplayName("never to an email-anon: identity, another integration's identity, or a principal that is not an email identity")
    void unauthenticatedIdentity() {
        assertEquals("unauthenticated_identity", decide(settings(), inbound(State.DONE), mail(), EmailUserIdentity.anonymous()).refusal());
        assertEquals("unauthenticated_identity",
                decide(settings(), inbound(State.DONE), mail(), EmailUserIdentity.perSender("ffffffffffffffffffffffff", SENDER)).refusal());
        assertEquals("unauthenticated_identity", decide(settings(), inbound(State.DONE), mail(), "alice").refusal());
        assertEquals("unauthenticated_identity", decide(settings(), inbound(State.DONE), mail(), null).refusal());
        assertEquals("unauthenticated_identity", decide(settings(), inbound(State.DONE), mail(), "email:" + INTEGRATION + ":").refusal());
    }

    @Test
    @DisplayName("never when the step's mail is not authenticated, even under an authenticated identity")
    void unauthenticatedSender() {
        for (String verdict : List.of("fail", "none", "pass-unaligned", "")) {
            assertEquals("unauthenticated_sender", decide(settings(), inbound(State.DONE), mailWith("authentication", verdict), PER_SENDER).refusal(),
                    verdict);
        }
        Map<String, Object> missing = new HashMap<>(mail());
        missing.remove("authentication");
        assertEquals("unauthenticated_sender", decide(settings(), inbound(State.DONE), missing, PER_SENDER).refusal());
    }

    @Test
    @DisplayName("never for a mail that did not reach a turn, or that is not this integration's inbound mail")
    void inboundRow() {
        for (State state : List.of(State.RECEIVED, State.CLAIMED, State.REFUSED)) {
            assertEquals("mail_not_run", decide(settings(), inbound(state), mail(), PER_SENDER).refusal(), state.name());
        }
        LedgerEntry other = inbound(State.DONE);
        LedgerEntry otherIntegration = new LedgerEntry(other.id(), "ffffffffffffffffffffffff", Kind.TRIGGER, Direction.IN, other.messageId(),
                other.conversationId(), other.sourceRef(), other.idempotencyKey(), other.recipient(), State.DONE, 1, null, null, null, null,
                other.createdAt(), other.updatedAt(), other.expiresAt());
        assertEquals("no_inbound_mail", decide(settings(), otherIntegration, mail(), PER_SENDER).refusal());
        LedgerEntry outbound = new LedgerEntry(other.id(), INTEGRATION, Kind.REPLY, Direction.OUT, other.messageId(), other.conversationId(),
                other.sourceRef(), other.idempotencyKey(), other.recipient(), State.SENT, 1, null, null, null, null, other.createdAt(),
                other.updatedAt(), other.expiresAt());
        assertEquals("no_inbound_mail", decide(settings(), outbound, mail(), PER_SENDER).refusal());
        LedgerEntry notice = new LedgerEntry(other.id(), INTEGRATION, Kind.NOTICE, Direction.IN, other.messageId(), other.conversationId(),
                other.sourceRef(), other.idempotencyKey(), other.recipient(), State.DONE, 1, null, null, null, null, other.createdAt(),
                other.updatedAt(), other.expiresAt());
        assertEquals("no_inbound_mail", decide(settings(), notice, mail(), PER_SENDER).refusal(), "an inbound row that is not a mail turn");
        assertEquals("no_inbound_mail", decide(settings(), null, mail(), PER_SENDER).refusal());
        assertEquals("no_sender",
                decide(settings(), inbound("in-2", EmailReplyTestSupport.MESSAGE_ID, null, State.DONE), mail(), PER_SENDER).refusal());
    }

    @Test
    @DisplayName("never when the conversation step is a different mail, or names a different From than the ledger")
    void stepMismatch() {
        assertEquals("mail_step_missing", decide(settings(), inbound(State.DONE), null, PER_SENDER).refusal());
        assertEquals("mail_step_missing", decide(settings(), inbound(State.DONE), mailWith("messageId", "<other@x.example>"), PER_SENDER).refusal());
        assertEquals("sender_mismatch", decide(settings(), inbound(State.DONE), mailWith("from", "mallory@supplier.com"), PER_SENDER).refusal());
    }

    @Test
    @DisplayName("perSender + originalSenderOnly: the conversation runs as that very address")
    void identityMismatch() {
        assertEquals("identity_mismatch",
                decide(settings(), inbound(State.DONE), mail(), EmailUserIdentity.perSender(INTEGRATION, "bob@supplier.com")).refusal());
        assertTrue(decide(settings(Map.of("threadParticipants", "anyAuthenticatedParticipant")), inbound(State.DONE), mail(),
                EmailUserIdentity.perSender(INTEGRATION, "bob@supplier.com")).allowed(), "a participant continuing someone else's thread");
    }

    @Test
    @DisplayName("never Cc, never the Reply-To unless honorReplyTo with exactly one valid Reply-To")
    void replyTo() {
        Map<String, Object> withReplyTo = mailWith("replyTo", List.of("form-user@example.org"));
        withReplyTo.put("cc", List.of("boss@supplier.com"));
        assertEquals(SENDER, decide(settings(), inbound(State.DONE), withReplyTo, PER_SENDER).recipient(), "Reply-To ignored by default");
        var honoured = settings(Map.of("honorReplyTo", "true"));
        assertEquals("form-user@example.org", decide(honoured, inbound(State.DONE), withReplyTo, PER_SENDER).recipient());
        assertEquals(SENDER,
                decide(honoured, inbound(State.DONE), mailWith("replyTo", List.of("a@x.example", "b@x.example")), PER_SENDER).recipient(),
                "two Reply-To: back to the From");
        assertEquals(SENDER, decide(honoured, inbound(State.DONE), mailWith("replyTo", List.of("not an address")), PER_SENDER).recipient());
        assertEquals("own_address", decide(honoured, inbound(State.DONE), mailWith("replyTo", List.of(MAILBOX)), PER_SENDER).refusal());
    }

    @Test
    @DisplayName("never to the integration's own address")
    void ownAddress() {
        var decision = decide(settings(), inbound("in-3", EmailReplyTestSupport.MESSAGE_ID, MAILBOX, State.DONE),
                mail(EmailReplyTestSupport.MESSAGE_ID,
                        MAILBOX),
                EmailUserIdentity.perSender(INTEGRATION, MAILBOX));
        assertFalse(decision.allowed());
        assertEquals("own_address", decision.refusal());
        assertNull(decision.recipient());
    }
}
