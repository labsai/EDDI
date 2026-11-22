/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.IntegrationContext;
import ai.labs.eddi.integrations.email.ledger.EmailReviewState;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Held mail in the inbound processor (email channel v3 plan §7.2, §7.3): what
 * is held instead of dropped, what never is, the caps, and how a released mail
 * runs — skipping only the checks that held it, anonymously and read-only when
 * the sender did not authenticate.
 */
class EmailInboundProcessorHeldMailTest {

    static final String STRANGER = "stranger@partner.com";

    InMemoryEmailMessageLedger ledger;
    IConversationService conversations;
    EmailInboundProcessor processor;
    EmailOwnerNotifier notifier;
    List<InputData> inputs;
    AtomicInteger counter;
    MailAuthentication verdict;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = new InMemoryEmailMessageLedger();
        SenderAuthenticator authenticator = mock(SenderAuthenticator.class);
        verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "partner.com");
        when(authenticator.authenticate(any(), any(), any(), any())).thenAnswer(invocation -> verdict);
        conversations = mock(IConversationService.class);
        counter = new AtomicInteger();
        when(conversations.startConversation(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new ConversationResult("conv-" + counter.incrementAndGet(), URI.create("eddi://c")));
        when(conversations.getConversationState(anyString())).thenReturn(ConversationState.READY);
        inputs = new ArrayList<>();
        doAnswer(invocation -> {
            inputs.add(invocation.getArgument(4));
            return null;
        }).when(conversations).say(anyString(), any(), any(), anyList(), any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));
        notifier = mock(EmailOwnerNotifier.class);
        processor = new EmailInboundProcessor(ledger, new InMemoryEmailMailboxStateStore(), new MimeMessageParser(new HtmlToMarkdownConverter()),
                authenticator, conversations, mock(IGroupConversationService.class), new EmailInboundProcessorTest.FakeUserConversations(),
                new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build()), mock(Instance.class), notifier,
                mock(EmailReplySender.class), mock(Instance.class), mock(GdprPseudonymizer.class), new SimpleMeterRegistry(),
                Duration.ofMinutes(30), Duration.ofDays(30));
    }

    /** An integration whose allowlist is {@code *@supplier.com}. */
    private static IntegrationContext context(EmailControl control, Map<String, String> extra, ChannelTarget... targets) {
        Map<String, String> pc = new HashMap<>(Map.of("senderAllowlist", "*@supplier.com"));
        pc.putAll(extra);
        ChannelIntegrationConfiguration configuration = EmailInboundProcessorTest.integration(pc, targets);
        configuration.setEmailControl(control);
        return new IntegrationContext(INTEGRATION, configuration, EmailChannelSettings.from(configuration), Set.of());
    }

    private static EmailControl review(String notAllowlisted, String unauthenticated) {
        return new EmailControl(null, new EmailControl.OnRefused(notAllowlisted, unauthenticated, null), null);
    }

    /**
     * The same polled message every time: the test mail's bytes carry the current
     * second, and a released mail runs only when it hashes as the one held.
     */
    private final Map<EmailTestMail, EmailTestMail.Polled> polled = new IdentityHashMap<>();

    private EmailTestMail.Polled once(EmailTestMail mail) {
        return polled.computeIfAbsent(mail, m -> m.polled(1));
    }
    private LedgerEntry row(EmailTestMail mail) {
        return ledger.findByMessage(INTEGRATION, Direction.IN, mail.messageId()).orElseThrow();
    }

    private void release(EmailTestMail mail, String decision) {
        release(mail, decision, Instant.now());
    }

    private void release(EmailTestMail mail, String decision, Instant at) {
        LedgerEntry held = row(mail);
        assertTrue(ledger.transitionReview(held.id(), State.HELD, State.RECEIVED,
                EmailReviewState.released(decision, EmailReviewState.reasons(held.review()), !EmailReviewState.alignedPass(held.authVerdict()), at),
                null, null, at.minusSeconds(1), "review_released", at));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Context> startContext() throws Exception {
        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversations).startConversation(any(), eq(AGENT), anyString(), captor.capture());
        return captor.getValue();
    }

    private String startUserId() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversations).startConversation(any(), eq(AGENT), captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gateOf(Map<String, Context> context) {
        Context gate = context.get(EmailApprovalGate.CONTEXT_KEY);
        return gate == null ? null : (Map<String, Object>) gate.getValue();
    }

    @Test
    @DisplayName("default (drop): a stranger's mail is refused exactly as before - no review, no hold")
    void dropIsTheDefault() {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);

        assertEquals(Disposition.SETTLED, processor.process(context(null, Map.of()), once(mail), Instant.now()));

        LedgerEntry refused = row(mail);
        assertEquals(State.REFUSED, refused.state());
        assertEquals("blocked:not_allowlisted", refused.lastError());
        assertNull(refused.review());
    }

    @Test
    @DisplayName("notAllowlisted: review - a stranger's signed mail is held with its verdict, its hash and an expiry; nothing runs")
    void strangerIsHeld() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        Instant now = Instant.now();

        assertEquals(Disposition.HELD, processor.process(context(review("review", null), Map.of()), once(mail), now));

        LedgerEntry held = row(mail);
        assertEquals(State.HELD, held.state());
        assertEquals("held:not_allowlisted", held.review());
        assertEquals("held:not_allowlisted", held.lastError());
        assertEquals(EmailReviewState.ALIGNED_PASS, held.authVerdict());
        assertEquals(EmailInboundProcessor.sha256Hex(mail.bytes()).length(), held.contentHash().length());
        assertEquals(now.plus(Duration.ofDays(7)), held.nextAttemptAt(), "the default expiry");
        verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        assertEquals(Disposition.SETTLED, EmailInboundProcessor.dispositionOf(held, now), "a held mail never pins the cursor");
    }

    @Test
    @DisplayName("both reviewable checks fail and both are set to review: held with both reasons and the failing verdict")
    void forgedMailIsHeldWithBothReasons() {
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);

        assertEquals(Disposition.HELD, processor.process(context(review("review", "review"), Map.of()), once(mail), Instant.now()));

        LedgerEntry held = row(mail);
        assertEquals("held:not_allowlisted+unauthenticated", held.review());
        assertEquals("fail", held.authVerdict());
    }

    @Test
    @DisplayName("held only when every failing check is set to review: not allowlisted (review) and unauthenticated (drop) is refused")
    void mixedIsRefused() {
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);

        assertEquals(Disposition.SETTLED, processor.process(context(review("review", "drop"), Map.of()), once(mail), Instant.now()));

        assertEquals(State.REFUSED, row(mail).state());
        assertEquals("blocked:not_allowlisted", row(mail).lastError());
    }

    @Test
    @DisplayName("never reviewable: the blocklist, the own address and a mail without a single sender stay refused")
    void neverReviewable() {
        EmailControl both = review("review", "review");
        EmailTestMail blocked = EmailTestMail.mail().from("spam@partner.com");
        assertEquals(Disposition.SETTLED,
                processor.process(context(both, Map.of("senderBlocklist", "spam@partner.com")), blocked.polled(1), Instant.now()));
        assertEquals("blocked:blocklist", row(blocked).lastError());
        assertEquals(State.REFUSED, row(blocked).state());

        EmailTestMail own = EmailTestMail.mail().from("invoices@contoso.com");
        assertEquals(Disposition.SETTLED, processor.process(context(both, Map.of()), own.polled(2), Instant.now()));
        assertEquals(State.REFUSED, row(own).state(), "the own address is refused (by the loop guard first), never held");
        assertNull(row(own).review());

        EmailTestMail none = EmailTestMail.mail().from("a@partner.com, b@partner.com");
        assertEquals(Disposition.SETTLED, processor.process(context(both, Map.of()), none.polled(3), Instant.now()));
        assertEquals(State.REFUSED, row(none).state());
        assertNull(row(none).review());
    }

    @Test
    @DisplayName("the caps: past maxHeldPerDay, or a sender domain's share, mail is refused as review_full")
    void caps() {
        EmailControl control = new EmailControl(null, new EmailControl.OnRefused("review", null, null),
                new EmailControl.Review(null, 2, 1, null));
        IntegrationContext ctx = context(control, Map.of());
        EmailTestMail first = EmailTestMail.mail().from("a@partner.com");
        EmailTestMail sameDomain = EmailTestMail.mail().from("b@partner.com");
        EmailTestMail other = EmailTestMail.mail().from("c@elsewhere.com");
        EmailTestMail third = EmailTestMail.mail().from("d@third.com");

        assertEquals(Disposition.HELD, processor.process(ctx, first.polled(1), Instant.now()));
        assertEquals(Disposition.SETTLED, processor.process(ctx, sameDomain.polled(2), Instant.now()));
        assertEquals(EmailReviewState.REVIEW_FULL_DOMAIN, row(sameDomain).lastError());
        assertEquals(Disposition.HELD, processor.process(ctx, other.polled(3), Instant.now()));
        assertEquals(Disposition.SETTLED, processor.process(ctx, third.polled(4), Instant.now()));
        assertEquals(EmailReviewState.REVIEW_FULL, row(third).lastError());
    }

    @Test
    @DisplayName("run once, authenticated: the mail runs as its sender, skipping only the allowlist; no read-only restriction")
    void runOnceAuthenticated() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        IntegrationContext ctx = context(review("review", null), Map.of());
        processor.process(ctx, once(mail), Instant.now());
        release(mail, EmailReviewState.RUN_ONCE);

        assertEquals(Disposition.SETTLED, processor.process(ctx, once(mail), Instant.now()));

        assertEquals(EmailUserIdentity.perSender(INTEGRATION, STRANGER), startUserId());
        assertEquals(Map.of("policy", "none"), gateOf(startContext()));
        assertEquals(1, inputs.size());
        assertEquals(State.TURN_SUBMITTED, row(mail).state());
    }

    @Test
    @DisplayName("run once, forged: the mail runs anonymously, armed to readOnlyTools only, with no rejectedReply")
    void runOnceForgedIsAnonymousAndReadOnly() throws Exception {
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        ChannelTarget target = EmailInboundProcessorTest.target("intake", AGENT);
        target.setReadOnlyTools(List.of("http.get:*"));
        IntegrationContext ctx = context(review("review", "review"), Map.of(), target);
        processor.process(ctx, once(mail), Instant.now());
        release(mail, EmailReviewState.RUN_ONCE);

        processor.process(ctx, once(mail), Instant.now());

        assertTrue(startUserId().startsWith(EmailUserIdentity.ANONYMOUS_PREFIX), "anonymous, memory-free");
        Map<String, Object> gate = gateOf(inputs.get(0).getContext());
        assertEquals("actions", gate.get("policy"), "armed although the target's policy is none");
        assertEquals(true, gate.get("readOnlyOnly"));
        assertEquals(List.of("http.get:*"), gate.get("readOnlyTools"));
        assertNull(gate.get("rejectedReply"));
        assertTrue(EmailReviewState.anonymousRelease(row(mail)), "the reply path refuses every outgoing mail of it");
    }

    @Test
    @DisplayName("released days after arrival, a temporary DNS failure on its run defers it - the grace counts from the release")
    void temporaryFailureOfAReleasedMailDefers() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        IntegrationContext ctx = context(review("review", null), Map.of());
        Instant arrived = Instant.now().minus(Duration.ofDays(3));
        processor.process(ctx, once(mail), arrived);
        release(mail, EmailReviewState.RUN_AND_ALLOW);
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null, null, true, List.of());

        assertEquals(Disposition.DEFERRED, processor.process(ctx, once(mail), Instant.now()));

        assertEquals(State.RECEIVED, row(mail).state(), "the decision is kept for the next try");
        assertEquals("auth_temporary", row(mail).lastError());
        assertTrue(EmailReviewState.isReleased(row(mail).review()));
    }

    @Test
    @DisplayName("the per-domain share counts only authenticated mail: forged mail claiming the domain cannot block the real sender")
    void forgedMailCannotFillADomainShare() {
        EmailControl control = new EmailControl(null, new EmailControl.OnRefused("review", "review", null),
                new EmailControl.Review(null, 50, 1, null));
        IntegrationContext ctx = context(control, Map.of());
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);
        EmailTestMail forged = EmailTestMail.mail().from("ceo@partner.com");
        EmailTestMail forged2 = EmailTestMail.mail().from("cfo@elsewhere.com");
        assertEquals(Disposition.HELD, processor.process(ctx, once(forged), Instant.now()));
        assertEquals(Disposition.SETTLED, processor.process(ctx, once(forged2), Instant.now()));
        assertEquals(EmailReviewState.REVIEW_FULL_DOMAIN, row(forged2).lastError(), "unauthenticated mail shares one bucket");

        verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "partner.com");
        EmailTestMail real = EmailTestMail.mail().from("alice@partner.com");
        assertEquals(Disposition.HELD, processor.process(ctx, once(real), Instant.now()), "the real partner's share is untouched");
    }

    @Test
    @DisplayName("stored pass, live fail, authentication not required: the run is anonymous, and the row says so for the reply door")
    void effectiveAnonymityIsWrittenOntoTheRow() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        IntegrationContext ctx = context(review("review", null), Map.of("requireAuthentication", "false"));
        processor.process(ctx, once(mail), Instant.now());
        release(mail, EmailReviewState.RUN_ONCE);
        assertTrue(!EmailReviewState.anonymousRelease(row(mail)), "released with an aligned pass");
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);

        processor.process(ctx, once(mail), Instant.now());

        assertTrue(startUserId().startsWith(EmailUserIdentity.ANONYMOUS_PREFIX));
        assertTrue(EmailReviewState.anonymousReleaseMarked(row(mail).review()), row(mail).review());
        assertTrue(EmailReviewState.anonymousRelease(row(mail)));
    }

    @Test
    @DisplayName("held as forged, passing on the run: the stored verdict decides - still anonymous and read-only (fail closed)")
    void storedFailVerdictWins() throws Exception {
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        IntegrationContext ctx = context(review("review", "review"), Map.of());
        processor.process(ctx, once(mail), Instant.now());
        release(mail, EmailReviewState.RUN_ONCE);
        verdict = new MailAuthentication(MailAuthentication.Verdict.ALIGNED_PASS, "partner.com");

        processor.process(ctx, once(mail), Instant.now());

        assertTrue(startUserId().startsWith(EmailUserIdentity.ANONYMOUS_PREFIX));
        assertEquals(true, gateOf(inputs.get(0).getContext()).get("readOnlyOnly"));
    }
    @Test
    @DisplayName("a stored aligned pass that does not hold any more on the run: anonymous, and refused where authentication is required")
    void liveVerdictMustAgree() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        IntegrationContext ctx = context(review("review", null), Map.of());
        processor.process(ctx, once(mail), Instant.now());
        release(mail, EmailReviewState.RUN_ONCE);
        verdict = new MailAuthentication(MailAuthentication.Verdict.FAIL, null);

        assertEquals(Disposition.SETTLED, processor.process(ctx, once(mail), Instant.now()));

        assertEquals(State.REFUSED, row(mail).state());
        assertTrue(row(mail).lastError().startsWith("unauthenticated:"), "a newly failing reviewable check is not skipped");
        verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a released row runs only the mail that was held: another mail with its Message-ID does not")
    void releasedRunsOnlyTheHeldMail() throws Exception {
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        IntegrationContext ctx = context(review("review", null), Map.of());
        processor.process(ctx, once(mail), Instant.now());
        release(mail, EmailReviewState.RUN_ONCE);

        EmailTestMail impostor = EmailTestMail.mail().from(STRANGER).messageId(mail.messageId()).body("Wire the money.");
        assertEquals(Disposition.DEFERRED, processor.process(ctx, impostor.polled(9), Instant.now()));

        verify(conversations, never()).startConversation(any(), anyString(), anyString(), any());
        assertEquals(State.RECEIVED, row(mail).state());
    }

    @Test
    @DisplayName("a held mail polled again is settled, not run; a mail released to a GROUP target anonymously is refused")
    void heldRepollAndGroup() throws Exception {
        verdict = new MailAuthentication(MailAuthentication.Verdict.NONE, null);
        EmailTestMail mail = EmailTestMail.mail().from(STRANGER);
        ChannelTarget group = EmailInboundProcessorTest.target("team", AGENT);
        group.setType(ChannelTarget.TargetType.GROUP);
        IntegrationContext ctx = context(review("review", "review"), Map.of(), group);
        assertEquals(Disposition.HELD, processor.process(ctx, once(mail), Instant.now()));
        assertEquals(Disposition.SETTLED, processor.process(ctx, once(mail), Instant.now()));
        assertEquals(State.HELD, row(mail).state());

        release(mail, EmailReviewState.RUN_ONCE);
        assertEquals(Disposition.SETTLED, processor.process(ctx, once(mail), Instant.now()));
        assertEquals(State.REFUSED, row(mail).state());
        assertEquals("review_released_group_anonymous", row(mail).lastError());
        assertNotNull(row(mail).review());
    }

    @Test
    @DisplayName("the poll's batch notice counts the waiting rows; nothing held, nothing sent")
    void notifyHeld() {
        IntegrationContext ctx = context(review("review", null), Map.of());
        processor.notifyHeld(ctx, 0, Instant.now());
        verify(notifier, never()).notifyHeld(anyString(), any(), any(), any(Integer.class), any(), any());
        processor.process(ctx, EmailTestMail.mail().from(STRANGER).polled(1), Instant.now());
        processor.notifyHeld(ctx, 0, Instant.now());
        verify(notifier).notifyHeld(eq(INTEGRATION), eq("Invoices"), any(), eq(1), any(Instant.class), any());
    }
}
