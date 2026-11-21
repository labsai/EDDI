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
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.integrations.email.EmailApprovalNotes;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.EmailOwnerNotifier;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.CONVERSATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.INTEGRATION;
import static ai.labs.eddi.integrations.email.reply.EmailReplyTestSupport.MESSAGE_ID;
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
 * The reply side of the email approval gate (email plan v3 §8.1): an approved
 * held start puts the mail back for the poll without replying, a rejected held
 * mail is answered with {@code rejectedReply} (or not at all), and an approved
 * paused turn becomes an answered contact.
 */
class EmailReplySenderApprovalGateTest {

    InMemoryEmailMessageLedger ledger;
    IConversationService conversations;
    EmailReplySenderTest.RecordingTransport transport;
    EmailReplySender sender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
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
        Instance<AuditLedgerService> audits = mock(Instance.class);
        sender = new EmailReplySender(ledger, new InMemoryEmailSuppressionStore(), directory, conversations, transport,
                new EmailReplyComposer(new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build())),
                mock(EmailOwnerNotifier.class), audits, mock(GdprPseudonymizer.class), new SimpleMeterRegistry(), Runnable::run,
                Duration.ofDays(30), 3, Duration.ofMinutes(1));
    }

    private void storeInbound(String note) {
        LedgerEntry base = EmailReplyTestSupport.inbound(State.DONE);
        ledger.put(new LedgerEntry(base.id(), base.integrationId(), base.kind(), base.direction(), base.messageId(), base.conversationId(),
                base.sourceRef(), base.idempotencyKey(), base.recipient(), base.state(), base.attempts(), null, null, null, note, base.createdAt(),
                base.updatedAt(), base.expiresAt()));
    }

    private LedgerEntry stored() {
        return ledger.findById("in-1").orElseThrow();
    }

    private void conversation(ConversationState state, ConversationOutput... outputs) throws Exception {
        when(conversations.readConversation(CONVERSATION, true, false, List.of()))
                .thenReturn(snapshot(state, EmailUserIdentity.perSender(INTEGRATION, SENDER), outputs));
    }

    private static ConversationOutput rejectedStep(String reply) {
        ConversationOutput step = output(reply, mail());
        step.put(EmailApprovalGate.REJECTED_OUTPUT_KEY, EmailApprovalGate.REJECTED_OUTPUT_VALUE);
        return step;
    }

    @Test
    @DisplayName("an approved held start: the row goes back to RECEIVED for the poll, and nothing is sent now")
    void approvedStartReturnsMailToPoll() throws Exception {
        storeInbound(EmailApprovalNotes.AWAITING_START);
        conversation(ConversationState.READY, output("Hello, I am the order desk.", mail()));

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isEmpty());

        assertEquals(State.RECEIVED, stored().state());
        assertEquals(EmailApprovalNotes.START_APPROVED, stored().lastError());
        assertTrue(transport.recipients.isEmpty(), "the mail's own turn answers it");
    }

    @Test
    @DisplayName("a rejected held mail is answered with rejectedReply and its row noted rejected")
    void rejectedSendsRejectedReply() throws Exception {
        storeInbound(EmailApprovalNotes.AWAITING_START);
        conversation(ConversationState.READY, rejectedStep("We cannot do that by mail."));

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.REJECTED, NOW).isPresent());

        String text = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(transport.messages.get(0))).getContent()
                .toString();
        assertTrue(text.contains("We cannot do that by mail."), text);
        assertFalse(text.contains("declined"), "not the generic rejection text");
        assertEquals(State.DONE, stored().state());
        assertEquals(EmailApprovalNotes.REJECTED, stored().lastError());
    }

    @Test
    @DisplayName("a rejection without rejectedReply sends nothing")
    void rejectedWithoutReplySendsNothing() throws Exception {
        storeInbound("awaiting_human");
        conversation(ConversationState.READY, rejectedStep(null));

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.REJECTED, NOW).isEmpty());
        assertTrue(transport.recipients.isEmpty());
        assertEquals(EmailApprovalNotes.REJECTED, stored().lastError());
    }

    @Test
    @DisplayName("an approved paused turn becomes an answered contact")
    void approvedPauseClearsAwaitingNote() throws Exception {
        storeInbound("awaiting_human");
        conversation(ConversationState.READY, output("Refund issued.", mail()));

        assertTrue(sender.onResumeCompleted(CONVERSATION, HitlVerdict.APPROVED, NOW).isPresent());
        assertNull(stored().lastError());
    }

    @Test
    @DisplayName("the rejection marker is read from the mail's own step")
    void rejectedBeforeRunReadsMailStep() {
        assertTrue(EmailReplySender.rejectedBeforeRun(List.of(rejectedStep("x")), MESSAGE_ID));
        assertFalse(EmailReplySender.rejectedBeforeRun(List.of(output("x", mail())), MESSAGE_ID));
        assertFalse(EmailReplySender.rejectedBeforeRun(List.of(rejectedStep("x")), "<other@x>"));
    }
}
