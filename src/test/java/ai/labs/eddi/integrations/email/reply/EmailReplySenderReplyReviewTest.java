/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.EmailReplyReview;
import ai.labs.eddi.integrations.email.EmailApprovalNotes;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.EmailOwnerNotifier;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailSuppressionStore;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.CONVERSATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.INTEGRATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.NOW;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.SENDER;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.conversationConfig;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.mail;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.output;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reply side of reply review (email plan v3 §8.2): a reviewed turn's reply
 * goes only after an approval — the approver's text when they edited it, on
 * every attempt — never while it waits, after a rejection or an expiry; and no
 * notice of a reviewed turn carries what the agent wrote.
 */
class EmailReplySenderReplyReviewTest {

    InMemoryEmailMessageLedger ledger;
    IConversationService conversations;
    EmailReplySenderTest.RecordingTransport transport;
    EmailReplySender sender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        EmailIntegrationDirectory directory = mock(EmailIntegrationDirectory.class);
        var configuration = new ChannelIntegrationConfiguration();
        configuration.setName("Orders");
        configuration.setChannelType("email");
        configuration.setPlatformConfig(new HashMap<>(conversationConfig(Map.of())));
        when(directory.find(INTEGRATION))
                .thenReturn(
                        Optional.of(new EmailIntegration(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()))));
        conversations = mock(IConversationService.class);
        transport = new EmailReplySenderTest.RecordingTransport();
        sender = new EmailReplySender(ledger, new InMemoryEmailSuppressionStore(), directory, conversations, transport,
                new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build())),
                mock(EmailOwnerNotifier.class), mock(Instance.class), mock(GdprPseudonymizer.class), new SimpleMeterRegistry(), Runnable::run,
                Duration.ofDays(30), 3, Duration.ofMinutes(1));
        LedgerEntry base = EmailReplyTestSupport.inbound(State.DONE);
        ledger.put(new LedgerEntry(base.id(), base.integrationId(), base.kind(), base.direction(), base.messageId(), base.conversationId(),
                base.sourceRef(), base.idempotencyKey(), base.recipient(), base.state(), base.attempts(), null, null, null, "awaiting_human",
                base.createdAt(), base.updatedAt(), base.expiresAt(), null, null));
    }

    private static final String DRAFT = "Your refund is on its way.";

    /** The mail's step of a turn armed for reply review, in the given state. */
    @SuppressWarnings("unchecked")
    private static ConversationOutput reviewed(String decision, String editedText) {
        ConversationOutput step = output(DRAFT, mail());
        ((Map<String, Object>) step.get("context")).put(EmailReplyReview.CONTEXT_KEY, Map.of("mode", "always"));
        if (decision != null) {
            step.put(EmailReplyReview.OUTPUT_KEY, decision);
        }
        if (editedText != null) {
            step.put(EmailReplyReview.TEXT_OUTPUT_KEY, editedText);
        }
        return step;
    }

    private void conversation(ConversationState state, ConversationOutput... outputs) throws Exception {
        when(conversations.readConversation(CONVERSATION, true, false, List.of()))
                .thenReturn(snapshot(state, EmailUserIdentity.perSender(INTEGRATION, SENDER), outputs));
        ConversationMemorySnapshot stored = new ConversationMemorySnapshot();
        stored.setHitlPausedAt(Instant.ofEpochMilli(1_762_077_600_000L));
        stored.setHitlPauseReason("The reply waits for review.");
        when(conversations.getConversationMemorySnapshot(CONVERSATION)).thenReturn(stored);
    }

    private String sentText(int index) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(transport.messages.get(index))).getContent()
                .toString();
    }

    private LedgerEntry inbound() {
        return ledger.findById("in-1").orElseThrow();
    }

    @Test
    @DisplayName("an approved review sends the turn's reply once, as the mail's REPLY")
    void approvedSendsReply() throws Exception {
        conversation(ConversationState.READY, reviewed(EmailReplyReview.APPROVED, null));

        Optional<LedgerEntry> row = sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW);

        assertTrue(row.isPresent());
        assertTrue(row.get().sourceRef().contains("|reply|"), "a REPLY keyed by the step: " + row.get().sourceRef());
        assertTrue(sentText(0).contains(DRAFT));
        assertNull(inbound().lastError(), "an answered contact");
        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isEmpty(), "once");
        assertTrue(sender.onTurnCompleted(INTEGRATION, settings(), inbound(), CONVERSATION,
                conversations.readConversation(CONVERSATION, true, false, List.of()), NOW).isEmpty(), "the turn callback adds no second mail");
        assertEquals(1, transport.messages.size());
    }

    @Test
    @DisplayName("an edited review sends the approver's text, not the draft")
    void editedSendsApproversText() throws Exception {
        conversation(ConversationState.READY, reviewed(EmailReplyReview.EDITED, "Your refund of 20 EUR left today."));

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isPresent());

        String text = sentText(0);
        assertTrue(text.contains("Your refund of 20 EUR left today."), text);
        assertFalse(text.contains(DRAFT), text);
    }

    @Test
    @DisplayName("a retry of an edited reply re-reads the approver's text")
    void retryReadsEditedText() throws Exception {
        conversation(ConversationState.READY, reviewed(EmailReplyReview.EDITED, "Edited text."));
        transport.failures.add(new MailSendException(MailSendException.Category.CONNECT, false, "down", null));

        LedgerEntry row = sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).orElseThrow();
        assertTrue(transport.messages.isEmpty(), "the first attempt failed");

        sender.sweep(NOW.plus(Duration.ofHours(2)));

        assertEquals(1, transport.messages.size());
        assertTrue(sentText(0).contains("Edited text."));
        assertEquals(State.SENT, ledger.findById(row.id()).orElseThrow().state());
    }

    @Test
    @DisplayName("a rejected or expired review sends nothing and notes the mail's row")
    void rejectedSendsNothing() throws Exception {
        conversation(ConversationState.READY, reviewed(EmailReplyReview.REJECTED, null));

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.REJECTED, NOW).isEmpty());

        assertTrue(transport.messages.isEmpty());
        assertEquals(EmailApprovalNotes.REPLY_REJECTED, inbound().lastError());
        assertTrue(EmailApprovalNotes.NOT_A_CONTACT.contains(inbound().lastError()), "no answer reached the sender");
        assertTrue(ledger.countByConversationSince(CONVERSATION, Kind.REPLY, NOW.minus(Duration.ofDays(1))) == 0);
    }

    @Test
    @DisplayName("while the review waits nothing is sent: no reply, and no pause notice carrying the draft")
    void pendingSendsNothing() throws Exception {
        conversation(ConversationState.AWAITING_HUMAN, reviewed(EmailReplyReview.PENDING, null));

        assertTrue(sender.onPauseCommitted(CONVERSATION, "1762077600000", NOW).isEmpty());
        assertTrue(sender.ensureFor(settings(), inbound(), conversations.readConversation(CONVERSATION, true, false, List.of()), NOW) == false);
        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isEmpty(),
                "an earlier pause's approval that reached the review sends nothing");
        assertTrue(transport.messages.isEmpty());
    }

    @Test
    @DisplayName("another pause of a reviewed turn (a tool call) notifies the sender without any agent text")
    void otherPauseNoticeCarriesNoAgentText() throws Exception {
        conversation(ConversationState.AWAITING_HUMAN, reviewed(null, null));

        assertTrue(sender.onPauseCommitted(CONVERSATION, "1762077600000", NOW).isPresent());

        String text = sentText(0);
        assertFalse(text.contains(DRAFT), text);
        assertTrue(text.contains("waiting for a person to approve"), text);
    }

    @Test
    @DisplayName("a reviewed turn that ended without its review never sends its output")
    void endedUndecidedSendsNoAgentText() throws Exception {
        conversation(ConversationState.READY, reviewed(null, null));

        assertTrue(sender.onTurnCompleted(INTEGRATION, settings(), inbound(), CONVERSATION,
                conversations.readConversation(CONVERSATION, true, false, List.of()), NOW).isEmpty());
        sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW);

        for (int i = 0; i < transport.messages.size(); i++) {
            assertFalse(sentText(i).contains(DRAFT), "no agent text: " + sentText(i));
        }
    }

    @Test
    @DisplayName("an armed mail the email gate rejected before the agent ran still gets the target's rejectedReply")
    void preRunRejectionKeepsRejectedReply() throws Exception {
        ConversationOutput step = reviewed(null, null);
        step.put("output", List.of("We cannot do that by mail."));
        step.put(EmailApprovalGate.REJECTED_OUTPUT_KEY,
                EmailApprovalGate.REJECTED_OUTPUT_VALUE);
        conversation(ConversationState.READY, step);

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.REJECTED, NOW).isPresent());

        assertTrue(sentText(0).contains("We cannot do that by mail."), sentText(0));
    }

    @Test
    @DisplayName("an unknown review value is a rejection, never a send")
    void unknownValueIsRejection() {
        assertEquals(EmailReplyReview.State.REJECTED, EmailReplyReview.stateOf(reviewed("maybe", null)));
        assertNull(EmailReplySender.reviewedReplyText(List.of(reviewed("maybe", "x")), 0));
        assertEquals(DRAFT, EmailReplySender.reviewedReplyText(List.of(output(DRAFT, mail())), 0), "an unarmed turn sends what it wrote");
    }

    private static EmailChannelSettings settings() {
        return EmailChannelSettings.from(new HashMap<>(conversationConfig(Map.of())));
    }
}
