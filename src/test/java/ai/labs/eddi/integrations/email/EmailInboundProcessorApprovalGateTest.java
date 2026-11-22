/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ChannelTarget.ApprovalPolicy;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.IntegrationContext;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.InMemoryEmailMessageLedger;
import ai.labs.eddi.integrations.email.reply.EmailReplySender;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.labs.eddi.integrations.email.EmailInboundProcessorTest.AGENT;
import static ai.labs.eddi.integrations.email.EmailInboundProcessorTest.INTEGRATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email approval gate in the inbound processor (email plan v3 §8.1): which
 * mail turns carry the target's gate, which wait before the agent runs, and how
 * a new conversation's held mail comes back after the approval.
 */
class EmailInboundProcessorApprovalGateTest {

    InMemoryEmailMessageLedger ledger;
    IConversationService conversations;
    EmailInboundProcessor processor;
    List<InputData> inputs;
    List<ConversationResponseHandler> handlers;
    AtomicInteger counter;
    MailAuthentication verdict;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        SenderAuthenticator authenticator = mock(SenderAuthenticator.class);
        verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "supplier.com");
        when(authenticator.authenticate(any(), any(), any(), any())).thenAnswer(invocation -> verdict);
        conversations = mock(IConversationService.class);
        counter = new AtomicInteger();
        when(conversations.startConversation(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new ConversationResult("conv-" + counter.incrementAndGet(), URI.create("eddi://c")));
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        inputs = new ArrayList<>();
        handlers = new ArrayList<>();
        doAnswer(invocation -> {
            inputs.add(invocation.getArgument(4));
            handlers.add(invocation.getArgument(6));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        processor = new EmailInboundProcessor(ledger, new InMemoryEmailMailboxStateStore(), new MimeMessageParser(new HtmlToMarkdownConverter()),
                authenticator, conversations, mock(IGroupConversationService.class), new EmailInboundProcessorTest.FakeUserConversations(),
                new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), attachments, mock(EmailOwnerNotifier.class),
                mock(EmailReplySender.class), mock(Instance.class), mock(GdprPseudonymizer.class), new SimpleMeterRegistry(),
                Duration.ofMinutes(30), Duration.ofDays(30));
    }

    private static ChannelTarget target(ApprovalPolicy policy, String... readOnlyTools) {
        ChannelTarget target = EmailInboundProcessorTest.target("intake", AGENT);
        target.setApprovalPolicy(policy);
        target.setReadOnlyTools(readOnlyTools.length == 0 ? null : List.of(readOnlyTools));
        return target;
    }

    private static IntegrationContext context(Map<String, String> extra, ChannelTarget target) {
        ChannelIntegrationConfiguration configuration = EmailInboundProcessorTest.integration(extra, target);
        return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration.getPlatformConfig()), Set.of());
    }

    private LedgerEntry row(EmailTestMail mail) {
        return ledger.findByMessage(INTEGRATION, Direction.IN, mail.messageId()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gateOf(Map<String, Context> context) {
        Context gate = context.get(EmailApprovalGate.CONTEXT_KEY);
        return gate == null ? null : (Map<String, Object>) gate.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Context> startContext() throws Exception {
        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversations).startConversation(any(), eq(AGENT), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("none (an old configuration): every turn says so explicitly, so the conversation records it has no gate")
    void noneCarriesNoGate() throws Exception {
        processor.process(context(Map.of(), target(null)), EmailTestMail.mail().polled(1), Instant.now());

        assertEquals(Map.of("policy", "none"), gateOf(startContext()));
        assertEquals(Map.of("policy", "none"), gateOf(inputs.get(0).getContext()));
    }

    @Test
    @DisplayName("actions: the start and the mail turn carry the gate, and neither waits before the run")
    void actionsCarriesGate() throws Exception {
        processor.process(context(Map.of(), target(ApprovalPolicy.ACTIONS, "http.get:*")), EmailTestMail.mail().polled(1), Instant.now());

        Map<String, Object> startGate = gateOf(startContext());
        assertNotNull(startGate, "CONVERSATION_START can call tools too");
        assertEquals(false, startGate.get("preRun"));
        Map<String, Object> turnGate = gateOf(inputs.get(0).getContext());
        assertEquals("actions", turnGate.get("policy"));
        assertEquals(List.of("http.get:*"), turnGate.get("readOnlyTools"));
        assertEquals(false, turnGate.get("preRun"));
    }

    @Test
    @DisplayName("everyTurn: a new mail starts its conversation paused, submits no turn and closes its row as awaiting the start")
    void everyTurnHoldsNewConversation() throws Exception {
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        EmailTestMail mail = EmailTestMail.mail();

        assertEquals(Disposition.SETTLED, processor.process(context(Map.of(), target(ApprovalPolicy.EVERY_TURN)), mail.polled(1), Instant.now()));

        Map<String, Context> start = startContext();
        assertEquals(true, gateOf(start).get("preRun"));
        assertNotNull(start.get("mail"), "the held mail rides on the start step for the notice and the approver");
        verify(conversations, never()).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(),
                any(ConversationResponseHandler.class));
        LedgerEntry held = row(mail);
        assertEquals(State.DONE, held.state());
        assertEquals("conv-1", held.conversationId());
        assertEquals(EmailApprovalNotes.AWAITING_START, held.lastError());
    }

    @Test
    @DisplayName("everyTurn: after the start was approved the mail runs in that conversation without waiting again")
    void approvedStartRunsOnce() throws Exception {
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        EmailTestMail mail = EmailTestMail.mail();
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.EVERY_TURN));
        processor.process(ctx, mail.polled(1), Instant.now());
        // What EmailReplySender does on the approval:
        Instant now = Instant.now();
        assertTrue(ledger.transition(row(mail).id(), State.DONE, State.RECEIVED, null, now, EmailApprovalNotes.START_APPROVED, false, now));
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.READY);

        processor.process(ctx, mail.polled(1), Instant.now());

        verify(conversations, times(1)).startConversation(any(), anyString(), anyString(), any());
        assertEquals(1, inputs.size());
        assertEquals(false, gateOf(inputs.get(0).getContext()).get("preRun"), "one approval, one wait");
    }

    @Test
    @DisplayName("everyTurn: a mail to a continued conversation waits in its own turn")
    void everyTurnContinued() throws Exception {
        EmailTestMail mail = EmailTestMail.mail();
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.EVERY_TURN));
        // The start did not pause (say an agent without workflows): the turn carries
        // the wait.
        processor.process(ctx, mail.polled(1), Instant.now());

        assertEquals(true, gateOf(inputs.get(0).getContext()).get("preRun"));
    }

    @Test
    @DisplayName("firstContact: a sender's first mail waits; once a turn of theirs ran for this target, the next does not")
    void firstContact() throws Exception {
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.FIRST_CONTACT));
        EmailTestMail first = EmailTestMail.mail();
        processor.process(ctx, first.polled(1), Instant.now());
        assertEquals(true, gateOf(inputs.get(0).getContext()).get("preRun"), "no earlier contact");
        // The first turn ran to an answer for the target's agent.
        handlers.get(0).onComplete(new SimpleConversationMemorySnapshot());
        var earlier = new SimpleConversationMemorySnapshot();
        earlier.setAgentId(AGENT);
        when(conversations.readConversation(eq("conv-1"), any(), any(), any())).thenReturn(earlier);

        processor.process(ctx, EmailTestMail.mail().polled(2), Instant.now());

        assertEquals(false, gateOf(inputs.get(1).getContext()).get("preRun"), "an earlier answered mail is a contact");
    }

    @Test
    @DisplayName("firstContact: an earlier contact with another agent, or a rejected one, does not count")
    void firstContactPerTargetAndAnswered() throws Exception {
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.FIRST_CONTACT));
        processor.process(ctx, EmailTestMail.mail().polled(1), Instant.now());
        handlers.get(0).onComplete(new SimpleConversationMemorySnapshot());
        var otherAgent = new SimpleConversationMemorySnapshot();
        otherAgent.setAgentId("ffffffffffffffffffffffff");
        when(conversations.readConversation(eq("conv-1"), any(), any(), any())).thenReturn(otherAgent);

        processor.process(ctx, EmailTestMail.mail().polled(2), Instant.now());

        assertEquals(true, gateOf(inputs.get(1).getContext()).get("preRun"));
    }

    @Test
    @DisplayName("firstContact: an unauthenticated sender is always a first contact")
    void unauthenticatedAlwaysFirst() {
        LedgerEntry any = mock(LedgerEntry.class);
        assertTrue(processor.isFirstContact(INTEGRATION, "alice@supplier.com", false, target(ApprovalPolicy.FIRST_CONTACT), any));
    }

    @Test
    @DisplayName("rejectedReply: the target's text, the default in conversation mode, nothing in trigger mode")
    void rejectedReplyDefaults() {
        var target = target(ApprovalPolicy.EVERY_TURN);
        var conversationMode = EmailChannelSettings.from(conversationModeConfig());
        assertEquals(EmailInboundProcessor.DEFAULT_REJECTED_REPLY, EmailInboundProcessor.rejectedReply(target, conversationMode));
        target.setRejectedReply("We cannot do that by mail.");
        assertEquals("We cannot do that by mail.", EmailInboundProcessor.rejectedReply(target, conversationMode));
        var triggerMode = EmailChannelSettings.from(EmailInboundProcessorTest.integration(Map.of(), target).getPlatformConfig());
        assertNull(EmailInboundProcessor.rejectedReply(target, triggerMode));
    }

    @Test
    @DisplayName("the approver's pause reason names neither the address nor the subject")
    void pauseReasonHasNoPersonalData() {
        String reason = EmailInboundProcessor.preRunReason(target(ApprovalPolicy.EVERY_TURN), true);
        assertFalse(reason.contains("@"));
        assertTrue(reason.contains("intake"));
    }

    private static Map<String, String> conversationModeConfig() {
        Map<String, String> pc = new HashMap<>(EmailInboundProcessorTest.integration(Map.of(), target(ApprovalPolicy.NONE)).getPlatformConfig());
        pc.put("mode", "conversation");
        pc.put("smtpHost", "smtp.contoso.com");
        return pc;
    }
}
