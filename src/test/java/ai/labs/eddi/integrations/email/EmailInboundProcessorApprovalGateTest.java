/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ChannelTarget.ApprovalPolicy;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
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
import ai.labs.eddi.engine.security.EmailReplyReview;
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
import java.util.IdentityHashMap;
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
        assertEquals(EmailApprovalNotes.AWAITING_START, held.approvalState(), "the hold has its own field");
        assertNotNull(held.contentHash(), "the hold is bound to the mail's content");
        assertEquals(64, held.contentHash().length());
        assertNotNull(held.nextAttemptAt(), "the held-mail sweep looks at it after stranded-after");
    }

    @Test
    @DisplayName("everyTurn: the approver is shown the held mail - sender, subject, attachments, the start of the text")
    void heldMailIsShownToTheApprover() throws Exception {
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        EmailTestMail mail = EmailTestMail.mail().subject("Refund order 7").body("Please refund order 7 to IBAN X.")
                .attachment("receipt.pdf", "application/pdf", "%PDF-1.4");

        processor.process(context(Map.of(), target(ApprovalPolicy.EVERY_TURN)), mail.polled(1), Instant.now());

        @SuppressWarnings("unchecked")
        Map<String, Object> shown = (Map<String, Object>) gateOf(startContext()).get("mail");
        assertNotNull(shown);
        assertEquals("alice@supplier.com", shown.get("from"));
        assertEquals("Refund order 7", shown.get("subject"));
        assertEquals(List.of("receipt.pdf"), shown.get("attachments"));
        assertTrue(String.valueOf(shown.get("excerpt")).contains("Please refund order 7"));
        assertFalse(EmailInboundProcessor.preRunReason(target(ApprovalPolicy.EVERY_TURN), true).contains("Refund"),
                "the pause reason, which the audit ledger keeps, stays without it");
    }

    @Test
    @DisplayName("a mail that does not wait carries no copy of itself in the gate")
    void noHeldMailWithoutPreRun() throws Exception {
        processor.process(context(Map.of(), target(ApprovalPolicy.ACTIONS)), EmailTestMail.mail().polled(1), Instant.now());

        assertNull(gateOf(inputs.get(0).getContext()).get("mail"));
    }

    /**
     * One raw form of {@code mail}: its Date header is taken once, so two polls
     * fetch the same bytes.
     */
    private final Map<EmailTestMail, byte[]> raws = new IdentityHashMap<>();

    private byte[] rawOf(EmailTestMail mail) {
        return raws.computeIfAbsent(mail, EmailTestMail::bytes);
    }

    private EmailTestMail.Polled polledOnce(EmailTestMail mail, long uid) {
        return new EmailTestMail.Polled(uid, rawOf(mail), mail.messageId(), Instant.now(), false);
    }

    /** What EmailReplySender#settleHeldStart does on an approval. */
    private void approve(EmailTestMail mail) {
        Instant now = Instant.now();
        String id = row(mail).id();
        assertTrue(ledger.transitionApproval(id, EmailApprovalNotes.AWAITING_START, EmailApprovalNotes.START_APPROVED, null, now));
        assertTrue(ledger.transition(id, State.DONE, State.RECEIVED, null, now, EmailApprovalNotes.START_APPROVED, false, now));
    }

    @Test
    @DisplayName("everyTurn: after the start was approved the mail runs in that conversation without waiting again")
    void approvedStartRunsOnce() throws Exception {
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        EmailTestMail mail = EmailTestMail.mail();
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.EVERY_TURN));
        processor.process(ctx, polledOnce(mail, 1), Instant.now());
        approve(mail);
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.READY);

        processor.process(ctx, polledOnce(mail, 1), Instant.now());

        verify(conversations, times(1)).startConversation(any(), anyString(), anyString(), any());
        assertEquals(1, inputs.size());
        assertEquals(false, gateOf(inputs.get(0).getContext()).get("preRun"), "one approval, one wait");
    }

    @Test
    @DisplayName("an approved start binds the mail that was held: another mail with its Message-ID waits for approval again")
    void approvalIsBoundToTheContent() throws Exception {
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        EmailTestMail approved = EmailTestMail.mail().body("Please send the invoice copy.");
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.EVERY_TURN));
        processor.process(ctx, polledOnce(approved, 1), Instant.now());
        approve(approved);
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.READY);
        // The re-fetch finds a different mail under the same Message-ID, from the same
        // (claimed) sender.
        EmailTestMail other = EmailTestMail.mail().messageId(approved.messageId()).body("Wire 10000 EUR to account X.");

        processor.process(ctx, polledOnce(other, 1), Instant.now());

        assertEquals(1, inputs.size());
        assertEquals(true, gateOf(inputs.get(0).getContext()).get("preRun"), "the approval does not carry over to other content");
        assertEquals("conv-1", row(approved).conversationId());
    }

    @Test
    @DisplayName("an approval survives a deferral: the retry after conversation_busy runs without a second wait")
    void approvalSurvivesDeferral() throws Exception {
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        EmailTestMail mail = EmailTestMail.mail();
        IntegrationContext ctx = context(Map.of(), target(ApprovalPolicy.EVERY_TURN));
        processor.process(ctx, polledOnce(mail, 1), Instant.now());
        approve(mail);
        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.IN_PROGRESS);

        assertEquals(Disposition.DEFERRED, processor.process(ctx, polledOnce(mail, 1), Instant.now()));
        LedgerEntry deferred = row(mail);
        assertEquals("conversation_busy", deferred.lastError(), "the deferral rewrote the note");
        assertEquals(EmailApprovalNotes.START_APPROVED, deferred.approvalState(), "but not the approval");

        when(conversations.getConversationState("conv-1")).thenReturn(ConversationState.READY);
        Instant later = Instant.now().plus(Duration.ofMinutes(10));
        processor.process(ctx, polledOnce(mail, 1), later);

        assertEquals(1, inputs.size());
        assertEquals(false, gateOf(inputs.get(0).getContext()).get("preRun"));
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

    // --- Reply review (email plan v3 §8.2) ----------------------------------

    private static IntegrationContext reviewContext(Map<String, String> pc, EmailControl.ReplyReview mode, String... internalDomains) {
        ChannelTarget target = target(ApprovalPolicy.NONE);
        ChannelIntegrationConfiguration configuration = EmailInboundProcessorTest.integration(pc, target);
        configuration.setEmailControl(new EmailControl(null, mode, internalDomains.length == 0 ? null : List.of(internalDomains)));
        return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration), Set.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> reviewOf(Map<String, Context> context) {
        Context review = context.get(EmailReplyReview.CONTEXT_KEY);
        return review == null ? null : (Map<String, Object>) review.getValue();
    }

    @Test
    @DisplayName("replyReview always: the mail's turn is armed, the conversation's start is not, the reason names no address")
    void replyReviewAlwaysArmsTheMailTurn() throws Exception {
        processor.process(reviewContext(conversationModeConfig(), EmailControl.ReplyReview.ALWAYS), EmailTestMail.mail().polled(1), Instant.now());

        Map<String, Object> review = reviewOf(inputs.get(0).getContext());
        assertNotNull(review);
        assertEquals("always", review.get("mode"));
        assertFalse(String.valueOf(review.get("reason")).contains("@"), "the reason reaches the audit ledger");
        assertNull(reviewOf(startContext()), "a reply answers one mail: only its turn is reviewed");
    }

    @Test
    @DisplayName("replyReview never (the default): no turn is armed")
    void replyReviewNeverArmsNothing() throws Exception {
        processor.process(reviewContext(conversationModeConfig(), null), EmailTestMail.mail().polled(1), Instant.now());

        assertNull(reviewOf(inputs.get(0).getContext()));
    }

    @Test
    @DisplayName("replyReview external: a reply to an internal domain goes, one to any other waits")
    void replyReviewExternalUsesTheRecipientsDomain() throws Exception {
        processor.process(reviewContext(conversationModeConfig(), EmailControl.ReplyReview.EXTERNAL, "supplier.com"), EmailTestMail.mail()
                .polled(1), Instant.now());
        assertNull(reviewOf(inputs.get(0).getContext()), "alice@supplier.com is internal");

        processor.process(reviewContext(conversationModeConfig(), EmailControl.ReplyReview.EXTERNAL, "*.supplier.com", "contoso.com"),
                EmailTestMail.mail().messageId("<m2@supplier.com>").polled(2), Instant.now());
        assertNotNull(reviewOf(inputs.get(1).getContext()), "*.supplier.com names subdomains only");
    }

    @Test
    @DisplayName("replyReview external under honorReplyTo judges the Reply-To, not the From")
    void replyReviewExternalUsesTheReplyTo() throws Exception {
        Map<String, String> pc = new HashMap<>(conversationModeConfig());
        pc.put("honorReplyTo", "true");

        // From is internal, the Reply-To is not: the reply goes outside, so it waits.
        processor.process(reviewContext(pc, EmailControl.ReplyReview.EXTERNAL, "supplier.com"),
                EmailTestMail.mail().header("Reply-To: bob@outside.example").polled(1), Instant.now());
        assertNotNull(reviewOf(inputs.get(0).getContext()), "the reply goes to bob@outside.example");

        // From is external, the Reply-To internal: the reply stays inside (documented
        // gap).
        processor.process(reviewContext(pc, EmailControl.ReplyReview.EXTERNAL, "outside.example"),
                EmailTestMail.mail().messageId("<m2@supplier.com>").header("Reply-To: carol@outside.example").polled(2), Instant.now());
        assertNull(reviewOf(inputs.get(1).getContext()), "the reply goes to carol@outside.example, which is internal here");
    }

    @Test
    @DisplayName("replyReview external without internal domains: every reply waits")
    void replyReviewExternalWithoutDomains() throws Exception {
        processor.process(reviewContext(conversationModeConfig(), EmailControl.ReplyReview.EXTERNAL), EmailTestMail.mail().polled(1), Instant.now());

        assertNotNull(reviewOf(inputs.get(0).getContext()));
    }

    @Test
    @DisplayName("replyReview firstContact: a sender's first mail waits")
    void replyReviewFirstContact() throws Exception {
        processor.process(reviewContext(conversationModeConfig(), EmailControl.ReplyReview.FIRST_CONTACT), EmailTestMail.mail().polled(1),
                Instant.now());

        assertEquals("firstContact", reviewOf(inputs.get(0).getContext()).get("mode"));
    }

    @Test
    @DisplayName("trigger mode never replies, so no turn is armed whatever the stored value says")
    void replyReviewTriggerMode() throws Exception {
        processor.process(reviewContext(Map.of(), EmailControl.ReplyReview.ALWAYS), EmailTestMail.mail().polled(1), Instant.now());

        assertNull(reviewOf(inputs.get(0).getContext()));
    }

    private static Map<String, String> conversationModeConfig() {
        Map<String, String> pc = new HashMap<>(EmailInboundProcessorTest.integration(Map.of(), target(ApprovalPolicy.NONE)).getPlatformConfig());
        pc.put("mode", "conversation");
        pc.put("smtpHost", "smtp.contoso.com");
        return pc;
    }
}
