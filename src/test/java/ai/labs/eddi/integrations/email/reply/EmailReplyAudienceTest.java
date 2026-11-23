/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who reads a thread reply (email v2 plan §9.3). Each rule is tested alone
 * against an otherwise copied colleague, so reverting any one of them fails a
 * test here.
 */
class EmailReplyAudienceTest {

    private static final String THREAD = EmailUserIdentity.thread(INTEGRATION, "<root@supplier.com>");
    private static final String COLLEAGUE = "bob@supplier.com";

    private static EmailChannelSettings thread(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>(Map.of("replyAudience", "thread", "actWhen", "addressed", "replyAllowedRecipients",
                "*@supplier.com", "aliases", "help@contoso.com"));
        pc.putAll(extra);
        return settings(pc);
    }

    private static Map<String, Object> mailTo(List<String> to, List<String> cc) {
        Map<String, Object> mail = new HashMap<>(mail());
        mail.put("to", to);
        mail.put("cc", cc);
        return mail;
    }

    private static EmailReplyAudience.Audience compute(EmailChannelSettings settings, Map<String, Object> mail, String userId) {
        return EmailReplyAudience.compute(INTEGRATION, settings, mail, SENDER, userId);
    }

    @Test
    @DisplayName("an internal colleague on the mail is copied, in header order, each once, never the To or the mailbox")
    void colleagues() {
        var audience = compute(thread(Map.of()), mailTo(List.of(MAILBOX, "Carol@Supplier.com"), List.of(COLLEAGUE, SENDER, "carol@supplier.com")),
                THREAD);
        assertEquals(SENDER, audience.to());
        assertEquals(List.of("carol@supplier.com", COLLEAGUE), audience.cc());
        assertFalse(audience.capped());
        assertEquals(Map.of("to", SENDER, "cc", List.of("carol@supplier.com", COLLEAGUE)), audience.toMap());
    }

    @Test
    @DisplayName("an external recipient is not copied: only replyAllowedRecipients are")
    void externalDropped() {
        var audience = compute(thread(Map.of()), mailTo(List.of(MAILBOX), List.of("eve@external.com", COLLEAGUE)), THREAD);
        assertEquals(List.of(COLLEAGUE), audience.cc());
        assertEquals(List.of(EmailReplyAudience.OWN, EmailReplyAudience.NOT_ALLOWED), audience.dropped());
    }

    @Test
    @DisplayName("a local part that routes onward (% or !) is never copied, even under an allowed domain")
    void sourceRoutedDropped() {
        var audience = compute(thread(Map.of()),
                mailTo(List.of(MAILBOX), List.of("eve%evil.com@supplier.com", "evil.com!eve@supplier.com", COLLEAGUE)),
                THREAD);
        assertEquals(List.of(COLLEAGUE), audience.cc());
        assertEquals(List.of(EmailReplyAudience.OWN, EmailReplyAudience.INVALID, EmailReplyAudience.INVALID), audience.dropped());
    }

    @Test
    @DisplayName("a list address is never copied, even when it is internal")
    void listDropped() {
        var audience = compute(thread(Map.of("listAddresses", "all-staff@supplier.com")),
                mailTo(List.of(MAILBOX), List.of("all-staff@supplier.com", COLLEAGUE)), THREAD);
        assertEquals(List.of(COLLEAGUE), audience.cc());
        assertEquals(List.of(EmailReplyAudience.OWN, EmailReplyAudience.LIST), audience.dropped());
    }

    @Test
    @DisplayName("the mailbox, its aliases and their plus-addresses are never copied, even when they match the allowlist")
    void ownDropped() {
        var settings = thread(Map.of("replyAllowedRecipients", "*@supplier.com,*@contoso.com"));
        var audience = compute(settings, mailTo(List.of(MAILBOX, "help@contoso.com"), List.of("support+billing@contoso.com", COLLEAGUE)), THREAD);
        assertEquals(List.of(COLLEAGUE), audience.cc());
        assertEquals(List.of(EmailReplyAudience.OWN, EmailReplyAudience.OWN, EmailReplyAudience.OWN), audience.dropped());
    }

    @Test
    @DisplayName("more copies than maxReplyRecipients: none at all, the reply goes to the sender alone")
    void capped() {
        var audience = compute(thread(Map.of("maxReplyRecipients", "2")),
                mailTo(List.of(MAILBOX), List.of("b@supplier.com", "c@supplier.com", "d@supplier.com")), THREAD);
        assertEquals(List.of(), audience.cc());
        assertTrue(audience.capped());
        assertEquals(Boolean.TRUE, audience.toMap().get("ccCapped"));
        assertEquals(2, compute(thread(Map.of("maxReplyRecipients", "2")), mailTo(List.of(MAILBOX), List.of("b@supplier.com", "c@supplier.com")),
                THREAD).cc().size(), "at the cap is fine");
    }

    @Test
    @DisplayName("no copies under replyAudience sender, nor for a conversation that is not this integration's thread identity")
    void onlyThreadIdentities() {
        var mail = mailTo(List.of(MAILBOX), List.of(COLLEAGUE));
        assertEquals(List.of(), compute(settings(), mail, THREAD).cc(), "the integration replies to the sender only");
        assertEquals(List.of(), compute(thread(Map.of()), mail, EmailUserIdentity.perSender(INTEGRATION, SENDER)).cc(),
                "a conversation started before the switch carries the sender's memory");
        assertEquals(List.of(), compute(thread(Map.of()), mail, EmailUserIdentity.thread("ffffffffffffffffffffffff", "<root@supplier.com>")).cc(),
                "another integration's thread");
        assertEquals(List.of(), compute(thread(Map.of()), mail, EmailUserIdentity.anonymous()).cc());
        assertEquals(List.of(), compute(thread(Map.of()), null, THREAD).cc());
    }

    @Test
    @DisplayName("a copy who was not sent every earlier reply is dropped; the rest stay (email v2 plan §9.3)")
    void lateJoinerIsNotCopied() {
        var mail = mailTo(List.of(MAILBOX), List.of(COLLEAGUE, "late@supplier.com"));
        var audience = EmailReplyAudience.compute(INTEGRATION, thread(Map.of()), mail, SENDER, THREAD,
                address -> !address.equals("late@supplier.com"));
        assertEquals(List.of(COLLEAGUE), audience.cc());
        assertEquals(List.of(EmailReplyAudience.OWN, EmailReplyAudience.LATE_JOINER), audience.dropped());
    }

    @Test
    @DisplayName("the ledger test: no reply sent yet admits everyone; otherwise only who was sent all of them; an unreadable ledger admits no one")
    void readEveryReplyFromTheLedger() {
        IEmailMessageLedger ledger = mock(IEmailMessageLedger.class);
        when(ledger.countSentReplies("fresh")).thenReturn(0L);
        assertTrue(EmailReplyAudience.readEveryReply(ledger, "fresh").test("anyone@supplier.com"), "nothing was said yet");
        assertTrue(EmailReplyAudience.readEveryReply(ledger, null).test("anyone@supplier.com"), "no conversation yet");

        when(ledger.countSentReplies("conv")).thenReturn(2L);
        when(ledger.countSentRepliesTo("conv", COLLEAGUE)).thenReturn(2L);
        when(ledger.countSentRepliesTo("conv", "late@supplier.com")).thenReturn(1L);
        var test = EmailReplyAudience.readEveryReply(ledger, "conv");
        assertTrue(test.test(COLLEAGUE));
        assertFalse(test.test("late@supplier.com"), "missed one reply");
        assertFalse(test.test("never@supplier.com"), "was sent none of them");

        when(ledger.countSentReplies("broken")).thenThrow(new IllegalStateException("store down"));
        assertFalse(EmailReplyAudience.readEveryReply(ledger, "broken").test(COLLEAGUE));

        when(ledger.countSentReplies("half")).thenReturn(2L);
        when(ledger.countSentRepliesTo("half", COLLEAGUE)).thenThrow(new IllegalStateException("store down"));
        assertFalse(EmailReplyAudience.readEveryReply(ledger, "half").test(COLLEAGUE),
                "the per-address read failing drops that copy, it does not throw");
    }

    @Test
    @DisplayName("narrowing between attempts keeps the stored order and never adds an address")
    void narrow() {
        assertEquals(List.of("a@x.com", "c@x.com"), EmailReplyAudience.narrow(List.of("a@x.com", "b@x.com", "c@x.com"), List.of("c@x.com", "a@x.com",
                "z@x.com")));
        assertEquals(List.of(), EmailReplyAudience.narrow(List.of(), List.of("z@x.com")), "a fresh address the row was never queued with");
    }

    @Test
    @DisplayName("the recipient decision carries the audience; the To rules are unchanged")
    void decision() {
        var mail = mailTo(List.of(MAILBOX), List.of(COLLEAGUE, "eve@external.com"));
        var decision = EmailReplyRecipients.decide(INTEGRATION, thread(Map.of()), inbound(State.DONE), mail, THREAD);
        assertTrue(decision.allowed(), decision.toString());
        assertEquals(SENDER, decision.recipient());
        assertEquals(List.of(COLLEAGUE), decision.cc());
        var sender = EmailReplyRecipients.decide(INTEGRATION, settings(), inbound(State.DONE), mail,
                EmailUserIdentity.perSender(INTEGRATION, SENDER));
        assertEquals(List.of(), sender.cc());
    }

    @Test
    @DisplayName("an email-thread: identity of this integration may be answered; of another integration it may not")
    void threadIdentityAccepted() {
        assertTrue(EmailReplyRecipients.decide(INTEGRATION, thread(Map.of()), inbound(State.DONE), mail(), THREAD).allowed(),
                "no identity_mismatch: the thread identity names no address");
        assertEquals("unauthenticated_identity",
                EmailReplyRecipients.decide(INTEGRATION, thread(Map.of()), inbound(State.DONE), mail(),
                        EmailUserIdentity.thread("ffffffffffffffffffffffff", "<root@supplier.com>")).refusal());
        assertEquals("unauthenticated_identity", EmailReplyRecipients
                .decide(INTEGRATION, thread(Map.of()), inbound(State.DONE), mail(), "email-thread:" + INTEGRATION + ":not-a-digest").refusal());
    }
}
