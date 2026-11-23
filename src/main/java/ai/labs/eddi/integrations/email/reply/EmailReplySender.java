/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.DeliveryReport;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MessageIds;
import ai.labs.eddi.engine.mail.PlainTextMailRenderer.RenderedMail;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.EmailReplyReview;
import ai.labs.eddi.integrations.email.EmailApprovalNotes;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.EmailMetrics;
import ai.labs.eddi.integrations.email.EmailOwnerNotifier;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore;
import ai.labs.eddi.integrations.email.ledger.LedgerRetry;
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Sends conversation-mode replies through the email ledger (email plan §9, §12,
 * §13, §16): the agent's answer to a mail, the in-thread notice when that turn
 * pauses for approval, the continuation after a decision, and a group
 * discussion's synthesis.
 * <p>
 * <b>Exactly once per (conversation, step, kind), cluster-wide.</b> Each reply
 * is a {@link Kind#REPLY} row under an idempotency key derived from those
 * three; only the caller whose insert wins sends, so a completion callback, the
 * stranded sweep and a second observer of the same event produce one mail. The
 * row is inserted already claimed with a lease and the first attempt starts at
 * once on a worker thread; {@link #sweep()} retries due rows with exponential
 * backoff ({@code eddi.email.outbound.retry-backoff}, doubling, capped at an
 * hour) until {@code eddi.email.outbound.max-attempts}. A permanent refusal, or
 * the last failed attempt, ends the row {@code FAILED} and tells the
 * integration's owners once a day
 * ({@link EmailOwnerNotifier.Reason#REPLY_FAILED}).
 * <p>
 * <b>No body in the ledger.</b> A retry re-reads the conversation and
 * re-renders the reply from the step it answers, under the Message-ID the row
 * was given. Group replies are the exception that proves the rule: a group
 * discussion keeps no copy of the mail it answered, so their rendered message
 * is held in this node's memory for its retries only — at most
 * {@value #MAX_CACHED_GROUP_REPLIES} of them, each for as long as its row can
 * stay retryable; on another node, after a restart or past that bound such a
 * row ends {@code FAILED} ({@code CONTENT_UNAVAILABLE}).
 * <p>
 * <b>Who it goes to</b> is {@link EmailReplyRecipients}' decision alone, on
 * every attempt. <b>Never</b> to a suppressed address (checked when the row is
 * written and before every attempt), and at most
 * {@code maxRepliesPerThreadPerHour} rows per conversation per hour (counted
 * from the ledger, cluster-wide). A reply over either limit is recorded
 * {@code SUPPRESSED}, never sent.
 * <p>
 * <b>Bounces.</b> {@link #applyDeliveryReport} reads a DSN that names a reply
 * this integration sent: a permanent failure for the reply's recipient ends the
 * row {@code FAILED} and suppresses the address. A DSN is untrusted mail, so it
 * counts only for a Message-ID EDDI minted (random, sent only to that
 * recipient) and only for that recipient's block.
 */
@ApplicationScoped
public class EmailReplySender {

    private static final Logger LOGGER = Logger.getLogger(EmailReplySender.class);

    /** How long a claim holds a row: covers connect and submission timeouts. */
    static final Duration LEASE = Duration.ofMinutes(5);
    static final int SWEEP_BATCH = 20;
    static final int MAX_CACHED_GROUP_REPLIES = 1000;

    static final String SUPPRESSED_RECIPIENT = "SUPPRESSED: a reply to this address bounced permanently";
    static final String THREAD_BUDGET = "THREAD_BUDGET: maxRepliesPerThreadPerHour reached";
    static final String NOT_CONVERSATION_MODE = "NOT_CONVERSATION_MODE: the integration no longer replies";
    static final String PAUSE_RESOLVED = "PAUSE_RESOLVED: the pause was decided before the notice went out";
    static final String CONTENT_UNAVAILABLE = "CONTENT_UNAVAILABLE: the reply cannot be rebuilt here";
    static final String RECIPIENT_REFUSED = "RECIPIENT_REFUSED: ";

    /** The kinds of reply, part of the idempotency key. */
    public enum ReplyKind {
        /** The agent's answer to a mail. */
        REPLY("reply"),
        /** The in-thread notice that the mail's turn waits for approval. */
        PAUSE("pause"),
        /** The continuation after the approval was decided. */
        RESUME("resume"),
        /** A group discussion's synthesis. */
        GROUP("group");

        private final String id;

        ReplyKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static ReplyKind fromId(String id) {
            for (ReplyKind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** What a reply is built from. */
    record Request(String integrationId, EmailChannelSettings settings, LedgerEntry inbound, String conversationId, String userId,
            Map<String, Object> stepMail, ReplyKind kind, String discriminator, String text) {
        @Override
        public String toString() {
            return "Request[" + kind + ", conversation=" + conversationId + "]";
        }
    }

    /** A reply ready to attempt. */
    record Prepared(EmailChannelSettings settings, RenderedMail mail) {
    }

    private final IEmailMessageLedger ledger;
    private final IEmailSuppressionStore suppressions;
    private final EmailIntegrationDirectory directory;
    private final IConversationService conversationService;
    private final MailTransport transport;
    private final EmailReplyComposer composer;
    private final EmailOwnerNotifier ownerNotifier;
    private final Instance<AuditLedgerService> auditLedger;
    private final GdprPseudonymizer pseudonymizer;
    private final EmailMetrics metrics;
    private final Executor executor;
    private final Duration retention;
    private final LedgerRetry retry;
    /**
     * How long a reply held for its S/MIME signature waits before it is given up.
     */
    private final Duration smimeMaxHold;

    /**
     * What this node's group replies were built from, by row id, so a retry can
     * choose the recipient and render the mail again under the current settings (a
     * group conversation keeps no copy of the mail it answered). An entry leaves
     * when its row ends here; one whose row another node's sweep finished ages out
     * once no row could still be retrying, and past the size bound one entry is
     * evicted, never the lot.
     */
    private final Cache<String, Request> groupReplies;

    @Inject
    public EmailReplySender(IEmailMessageLedger ledger, IEmailSuppressionStore suppressions, EmailIntegrationDirectory directory,
            IConversationService conversationService, ImapMailTransport transport, EmailReplyComposer composer, EmailOwnerNotifier ownerNotifier,
            Instance<AuditLedgerService> auditLedger, GdprPseudonymizer pseudonymizer, MeterRegistry meterRegistry, ManagedExecutor executor,
            @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d") Duration retention,
            @ConfigProperty(name = "eddi.email.outbound.max-attempts", defaultValue = "6") int maxAttempts,
            @ConfigProperty(name = "eddi.email.outbound.retry-backoff", defaultValue = "1m") Duration retryBackoff,
            @ConfigProperty(name = "eddi.email.smime.max-hold", defaultValue = "72h") Duration smimeMaxHold) {
        this(ledger, suppressions, directory, conversationService, (MailTransport) transport, composer, ownerNotifier, auditLedger, pseudonymizer,
                meterRegistry, (Executor) executor, retention, maxAttempts, retryBackoff, smimeMaxHold);
    }

    /** For tests: as below, with the default {@link #DEFAULT_SMIME_MAX_HOLD}. */
    public EmailReplySender(IEmailMessageLedger ledger, IEmailSuppressionStore suppressions, EmailIntegrationDirectory directory,
            IConversationService conversationService, MailTransport transport, EmailReplyComposer composer, EmailOwnerNotifier ownerNotifier,
            Instance<AuditLedgerService> auditLedger, GdprPseudonymizer pseudonymizer, MeterRegistry meterRegistry, Executor executor,
            Duration retention, int maxAttempts, Duration retryBackoff) {
        this(ledger, suppressions, directory, conversationService, transport, composer, ownerNotifier, auditLedger, pseudonymizer, meterRegistry,
                executor, retention, maxAttempts, retryBackoff, DEFAULT_SMIME_MAX_HOLD);
    }

    /**
     * For tests: any transport, any executor (a direct one runs the attempt
     * inline).
     */
    public EmailReplySender(IEmailMessageLedger ledger, IEmailSuppressionStore suppressions, EmailIntegrationDirectory directory,
            IConversationService conversationService, MailTransport transport, EmailReplyComposer composer, EmailOwnerNotifier ownerNotifier,
            Instance<AuditLedgerService> auditLedger, GdprPseudonymizer pseudonymizer, MeterRegistry meterRegistry, Executor executor,
            Duration retention, int maxAttempts, Duration retryBackoff, Duration smimeMaxHold) {
        this.ledger = ledger;
        this.suppressions = suppressions;
        this.directory = directory;
        this.conversationService = conversationService;
        this.transport = transport;
        this.composer = composer;
        this.ownerNotifier = ownerNotifier;
        this.auditLedger = auditLedger;
        this.pseudonymizer = pseudonymizer;
        this.metrics = new EmailMetrics(meterRegistry);
        this.executor = executor;
        this.retention = retention == null || retention.isZero() || retention.isNegative()
                ? Duration.ofDays(30)
                : retention.compareTo(MIN_RETENTION) < 0 ? MIN_RETENTION : retention;
        this.retry = new LedgerRetry(ledger, maxAttempts, retryBackoff);
        this.smimeMaxHold = saneMaxHold(smimeMaxHold, this.retention);
        // Every attempt can hold its lease and then wait the longest backoff. The
        // direct executor runs eviction on the writing thread, not some time later.
        this.groupReplies = Caffeine.newBuilder().maximumSize(MAX_CACHED_GROUP_REPLIES)
                .expireAfterWrite(groupReplyLifetime(retry.maxAttempts(), this.smimeMaxHold)).executor(Runnable::run).build();
    }

    // --- Entry points --------------------------------------------------------

    /**
     * A conversation-mode turn of {@code inbound} finished. Called from the turn's
     * completion callback before the inbound row moves to {@code DONE}, so a crash
     * after this leaves a reply row the sweep retries, and a crash before it a
     * {@code TURN_SUBMITTED} row the stranded sweep rebuilds the reply for. A turn
     * that paused gets its notice from {@link #onPauseCommitted}; a failed turn, or
     * one with no output, sends nothing.
     *
     * @return the reply row this call wrote, if any
     */
    public Optional<LedgerEntry> onTurnCompleted(String integrationId, EmailChannelSettings settings, LedgerEntry inbound, String conversationId,
                                                 SimpleConversationMemorySnapshot snapshot, Instant now) {
        if (settings == null || !settings.replies() || snapshot == null || conversationId == null) {
            // Trigger mode: nothing, ever.
            return Optional.empty();
        }
        ConversationState state = snapshot.getConversationState();
        if (state == ConversationState.AWAITING_HUMAN || state == ConversationState.ERROR || state == ConversationState.EXECUTION_INTERRUPTED) {
            return Optional.empty();
        }
        var outputs = snapshot.getConversationOutputs();
        int step = EmailReplyThread.mailStep(outputs, inbound.messageId());
        if (step < 0) {
            LOGGER.debugf("Email integration %s: no step of conversation %s carries mail %s; no reply", sanitize(integrationId),
                    sanitize(conversationId), inbound.id());
            return Optional.empty();
        }
        String text = reviewedReplyText(outputs, step);
        if (text == null) {
            LOGGER.debugf("Email integration %s: the reply of conversation %s to mail %s has no approved review; nothing is sent",
                    sanitize(integrationId), sanitize(conversationId), inbound.id());
            return Optional.empty();
        }
        if (text.isBlank()) {
            metrics.reply("no_output");
            return Optional.empty();
        }
        Request request = new Request(integrationId, settings, inbound, conversationId, snapshot.getUserId(),
                EmailReplyThread.mailOf(outputs.get(step)), ReplyKind.REPLY, Integer.toString(step), text);
        String held = EmailReplyReview.stateOf(outputs.get(step)) == EmailReplyReview.State.NOT_ARMED ? reviewNowRequired(request) : null;
        if (held != null) {
            return hold(request, held, now);
        }
        return enqueue(request, now);
    }

    /**
     * The {@code lastError} prefix of a reply held because it now needs a review.
     */
    static final String REVIEW_REQUIRED = "REVIEW_REQUIRED: ";

    /**
     * Why the reply of a turn that was not armed for review must not go out now, or
     * null: the integration's reply review asks for one under the current settings
     * - {@code always} since the mail arrived, or {@code external} and the
     * recipient (under the current {@code honorReplyTo}) is outside the current
     * {@code internalDomains}. Such a turn is over and cannot be reviewed any more,
     * so its reply is recorded and not sent.
     */
    static String reviewNowRequired(Request request) {
        EmailChannelSettings settings = request.settings();
        EmailControl.ReplyReview mode = settings.replyReview();
        if (mode == EmailControl.ReplyReview.ALWAYS) {
            return REVIEW_REQUIRED + "always";
        }
        if (mode == EmailControl.ReplyReview.EXTERNAL) {
            String sender = request.inbound().recipient();
            String recipient = sender == null ? null : EmailReplyRecipients.recipientOf(settings, sender, request.stepMail());
            if (recipient == null || !settings.isInternal(recipient)) {
                return REVIEW_REQUIRED + "external";
            }
        }
        return null;
    }

    /**
     * Records {@code request} as a SUPPRESSED reply row with {@code reason}; sends
     * nothing.
     */
    private Optional<LedgerEntry> hold(Request request, String reason, Instant now) {
        EmailReplyRecipients.Decision decision = EmailReplyRecipients.decide(request.integrationId(), request.settings(), request.inbound(),
                request.stepMail(), request.userId());
        if (!decision.allowed()) {
            return Optional.empty();
        }
        String key = idempotencyKey(request.conversationId(), request.inbound().id(), request.kind(), request.discriminator());
        LedgerEntry suppressed = new LedgerEntry(UUID.randomUUID().toString(), request.integrationId(), Kind.REPLY, Direction.OUT,
                MessageIds.generate(IEmailSuppressionStore.domainOf(request.settings().address())), request.conversationId(),
                sourceRef(request.inbound().id(), request.kind(), request.discriminator()), key, decision.recipient(), State.SUPPRESSED, 0, null,
                null, null, reason, now, now, now.plus(retention));
        if (ledger.insertIfAbsent(suppressed)) {
            metrics.reply("review_required");
            LOGGER.warnf("Email integration %s: the reply of conversation %s is not sent: the current reply review asks for one and its turn "
                    + "was not reviewed (%s)", sanitize(request.integrationId()), sanitize(request.conversationId()), reason);
        }
        return Optional.empty();
    }

    /**
     * The text the reply to the mail of step {@code step} carries, or null when it
     * must not be sent (email plan v3 §8.2): a turn armed for reply review sends
     * only once its review was approved — the approver's text when they edited it,
     * else the turn's — and nothing while it waits, after a rejection or an expiry,
     * or when it ended undecided. A turn that was not armed sends what it wrote.
     */
    static String reviewedReplyText(List<? extends Map<String, Object>> outputs, int step) {
        Map<String, Object> output = outputs.get(step);
        if (EmailApprovalGate.REJECTED_OUTPUT_VALUE.equals(output.get(EmailApprovalGate.REJECTED_OUTPUT_KEY))) {
            // Rejected before the agent ran: the step holds only the target's
            // rejectedReply, which no agent wrote.
            return EmailReplyThread.replyText(outputs, step);
        }
        return switch (EmailReplyReview.stateOf(output)) {
            case NOT_ARMED -> EmailReplyThread.replyText(outputs, step);
            case APPROVED -> {
                String edited = EmailReplyReview.editedText(output);
                yield edited != null ? edited : EmailReplyThread.replyText(outputs, step);
            }
            case UNDECIDED, PENDING, REJECTED -> null;
        };
    }

    /**
     * A group discussion a conversation-mode mail started has finished: its
     * synthesis is the reply. {@code mail} is the processor's own mail map (a group
     * conversation keeps none).
     */
    public Optional<LedgerEntry> onGroupCompleted(String integrationId, EmailChannelSettings settings, LedgerEntry inbound,
                                                  String groupConversationId,
                                                  String userId, Map<String, Object> mail, String synthesis, Instant now) {
        if (settings == null || !settings.replies() || synthesis == null || synthesis.isBlank() || inbound == null) {
            return Optional.empty();
        }
        if (settings.replyReview() != EmailControl.ReplyReview.NEVER) {
            // Reply review is refused with GROUP targets at save, but a document written
            // another way can still hold both. A group's synthesis has no turn that could
            // wait for review, so rather than go out unreviewed it is not sent.
            ledger.transition(inbound.id(), State.DONE, State.DONE, null, null, EmailApprovalNotes.REPLY_REVIEW_UNSUPPORTED, false, now);
            metrics.reply("review_unsupported");
            LOGGER.warnf("Email integration %s: replyReview is %s but mail %s went to a GROUP target, whose answer cannot wait for review; "
                    + "no reply is sent", sanitize(integrationId), settings.replyReview().json(), inbound.id());
            return Optional.empty();
        }
        return enqueue(new Request(integrationId, settings, inbound, groupConversationId, userId, mail, ReplyKind.GROUP, "synthesis", synthesis),
                now);
    }

    /**
     * A conversation paused for approval. When it is a conversation-mode email
     * conversation and the pause is on a mail's turn, its sender gets the notice in
     * the thread: the agent's output so far and the pause reason — never the tool
     * or its arguments (the same data minimisation as Slack's in-thread notice).
     */
    public Optional<LedgerEntry> onPauseCommitted(String conversationId, String pauseId, Instant now) {
        Optional<Located> located = locate(conversationId);
        if (located.isEmpty() || pauseId == null) {
            return Optional.empty();
        }
        SimpleConversationMemorySnapshot snapshot = readFull(conversationId);
        if (snapshot == null || snapshot.getConversationState() != ConversationState.AWAITING_HUMAN) {
            return Optional.empty();
        }
        return pauseRequest(located.get(), snapshot, pauseId).flatMap(request -> enqueue(request, now));
    }

    /**
     * A paused conversation was decided (or the pause cancelled). Its sender gets
     * what happened, and on approval the continuation. Who decided is never named:
     * approvers are staff, the sender is outside.
     */
    public Optional<LedgerEntry> onResumeCompleted(String conversationId, HitlVerdict verdict, Instant now) {
        // The email approval gate held this mail before its new conversation started
        // (email plan v3 §8.1): settled here in either mode — trigger mode too, which
        // sends nothing but must still hand an approved mail back to the poll.
        Optional<LedgerEntry> held = heldStartOf(conversationId);
        if (held.isPresent()) {
            SimpleConversationMemorySnapshot snapshot = readFull(conversationId);
            return snapshot == null ? Optional.empty() : settleHeldStart(held.get(), snapshot, verdict, now).reply();
        }
        Optional<Located> located = locate(conversationId);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        SimpleConversationMemorySnapshot snapshot = readFull(conversationId);
        if (snapshot == null) {
            return Optional.empty();
        }
        LedgerEntry inbound = located.get().inbound();
        Optional<LedgerEntry> reviewed = onReviewedTurnResumed(located.get(), snapshot, verdict, now);
        if (reviewed != null) {
            return reviewed;
        }
        boolean rejectedBeforeRun = rejectedBeforeRun(snapshot.getConversationOutputs(), inbound.messageId());
        if (rejectedBeforeRun) {
            ledger.transition(inbound.id(), State.DONE, State.DONE, null, null, EmailApprovalNotes.REJECTED, false, now);
        }
        Optional<LedgerEntry> reply = resumeRequest(located.get(), snapshot, verdict == null ? "NONE" : verdict.name())
                .flatMap(request -> enqueue(request, now));
        if (reply.isPresent() && !rejectedBeforeRun && verdict == HitlVerdict.APPROVED && snapshot.getConversationState() != ConversationState.ERROR
                && AWAITING_HUMAN_NOTE.equals(inbound.lastError())) {
            // The turn ran after all and its answer has a reply row: an answered contact
            // now (firstContact). A refused or throttled reply is none.
            ledger.transition(inbound.id(), State.DONE, State.DONE, null, null, null, false, now);
        }
        return reply;
    }

    /**
     * A resume of a mail turn armed for reply review (email plan v3 §8.2), or null
     * for any other turn. Never sends what the agent wrote unless the review
     * approved it:
     * <ul>
     * <li>the review approved — the reply, as a {@link ReplyKind#REPLY} keyed by
     * the mail's step, so a retry or an S/MIME hold re-reads the approved text and
     * the reply goes once;</li>
     * <li>the review rejected or expired — nothing; the mail's row notes
     * {@link EmailApprovalNotes#REPLY_REJECTED};</li>
     * <li>the review waits now (an earlier pause of the turn was approved and the
     * turn reached its review) — nothing;</li>
     * <li>the turn ended without reaching its review (a rejected step, nothing to
     * send) — the notice of the decision without any agent text.</li>
     * </ul>
     */
    private Optional<LedgerEntry> onReviewedTurnResumed(Located located, SimpleConversationMemorySnapshot snapshot, HitlVerdict verdict,
                                                        Instant now) {
        LedgerEntry inbound = located.inbound();
        var outputs = snapshot.getConversationOutputs();
        int step = EmailReplyThread.mailStep(outputs, inbound.messageId());
        // A mail the email gate rejected before the agent ran is answered with the
        // target's rejectedReply, which no agent wrote: the gate's own path.
        if (step < 0 || rejectedBeforeRun(outputs, inbound.messageId())) {
            return null;
        }
        EmailReplyReview.State review = EmailReplyReview.stateOf(outputs.get(step));
        switch (review) {
            case NOT_ARMED :
                return null;
            case APPROVED : {
                metrics.reply("review_approved");
                Optional<LedgerEntry> reply = replyRequest(located, snapshot, Integer.toString(step)).flatMap(request -> enqueue(request, now));
                // An answered contact (firstContact) only once a reply row exists: a
                // refused, suppressed or throttled reply, or nothing to send, is none.
                if (reply.isPresent() && AWAITING_HUMAN_NOTE.equals(inbound.lastError())) {
                    ledger.transition(inbound.id(), State.DONE, State.DONE, null, null, null, false, now);
                }
                return reply;
            }
            case REJECTED : {
                ledger.transition(inbound.id(), State.DONE, State.DONE, null, null, EmailApprovalNotes.REPLY_REJECTED, false, now);
                metrics.reply("review_rejected");
                LOGGER.infof("Email integration %s: the reply of conversation %s to mail %s was rejected in review; nothing is sent",
                        sanitize(inbound.integrationId()), sanitize(inbound.conversationId()), inbound.id());
                return Optional.empty();
            }
            case PENDING :
                return Optional.empty();
            default : {
                if (snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                    return Optional.empty();
                }
                String text = resumeText(verdict == null ? "NONE" : verdict.name(), snapshot.getConversationState(), null);
                String discriminator = (verdict == null ? "NONE" : verdict.name()) + "." + sha256(text).substring(0, 16);
                return enqueue(new Request(inbound.integrationId(), located.settings(), inbound, inbound.conversationId(), snapshot.getUserId(),
                        EmailReplyThread.mailOf(outputs.get(step)), ReplyKind.RESUME, discriminator, text), now);
            }
        }
    }

    /** The note the inbound processor closes a paused mail's row with. */
    static final String AWAITING_HUMAN_NOTE = "awaiting_human";

    /** The latest mail of {@code conversationId} when the email gate holds it. */
    private Optional<LedgerEntry> heldStartOf(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        try {
            return ledger.findInboundTurn(conversationId, true).filter(row -> EmailApprovalNotes.AWAITING_START.equals(row.approvalState()));
        } catch (RuntimeException e) {
            LOGGER.debugf("Ledger rows of conversation %s could not be read: %s", sanitize(conversationId), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * How a held start was settled ({@link #settleHeldStart}).
     *
     * @param outcome
     *            the row's new {@code approvalState}, or null when nothing changed
     *            (still waiting, or settled by someone else)
     * @param reply
     *            the notice queued to the sender, if any
     */
    public record HeldStartOutcome(String outcome, Optional<LedgerEntry> reply) {
        static HeldStartOutcome unchanged() {
            return new HeldStartOutcome(null, Optional.empty());
        }
    }

    /**
     * Settles a mail the email approval gate held before its conversation started
     * ({@link EmailApprovalNotes#AWAITING_START}), from what its conversation shows
     * — called by the resume observer and by the stranded sweep, which catches what
     * the observer missed (a node that died, an event that never came). Once: the
     * {@code approvalState} change is conditional, and only its winner acts.
     * <ul>
     * <li>still paused or running: nothing changes;</li>
     * <li>approved, and the start did not fail:
     * {@link EmailApprovalNotes#START_APPROVED}, the row back to {@code RECEIVED}
     * for the next poll;</li>
     * <li>rejected: {@link EmailApprovalNotes#REJECTED}, and in conversation mode
     * the target's {@code rejectedReply};</li>
     * <li>approved but the start ended in ERROR:
     * {@link EmailApprovalNotes#START_FAILED};</li>
     * <li>no verdict (the pause was cancelled):
     * {@link EmailApprovalNotes#START_CANCELLED}.</li>
     * </ul>
     * The last three tell the sender, in conversation mode, as a resume would (the
     * same reply key, so an observer and a sweep send it once).
     *
     * @param verdictHint
     *            the verdict of the resume event, used only when the step records
     *            none; may be null
     */
    public HeldStartOutcome settleHeldStart(LedgerEntry row, SimpleConversationMemorySnapshot snapshot, HitlVerdict verdictHint, Instant now) {
        if (row == null || snapshot == null || !EmailApprovalNotes.AWAITING_START.equals(row.approvalState())) {
            return HeldStartOutcome.unchanged();
        }
        ConversationState state = snapshot.getConversationState();
        if (state == ConversationState.AWAITING_HUMAN || state == ConversationState.IN_PROGRESS) {
            return HeldStartOutcome.unchanged();
        }
        var outputs = snapshot.getConversationOutputs();
        int step = outputs == null ? -1 : EmailReplyThread.mailStep(outputs, row.messageId());
        Object recorded = step >= 0 ? outputs.get(step).get(HITL_DECISION_OUTPUT) : null;
        String verdict = recorded != null ? String.valueOf(recorded) : verdictHint != null ? verdictHint.name() : null;
        String outcome;
        if (HitlVerdict.REJECTED.name().equals(verdict) || outputs != null && rejectedBeforeRun(outputs, row.messageId())) {
            outcome = EmailApprovalNotes.REJECTED;
        } else if (state == ConversationState.ERROR) {
            outcome = EmailApprovalNotes.START_FAILED;
        } else if (HitlVerdict.APPROVED.name().equals(verdict)) {
            outcome = EmailApprovalNotes.START_APPROVED;
        } else {
            outcome = EmailApprovalNotes.START_CANCELLED;
        }
        if (!ledger.transitionApproval(row.id(), EmailApprovalNotes.AWAITING_START, outcome, null, now)) {
            return HeldStartOutcome.unchanged();
        }
        if (EmailApprovalNotes.START_APPROVED.equals(outcome)) {
            // CONVERSATION_START has run; the mail itself goes back to the poll — its
            // turn answers it, so nothing is sent here.
            try {
                if (ledger.transition(row.id(), State.DONE, State.RECEIVED, null, now, EmailApprovalNotes.START_APPROVED, false, now)) {
                    LOGGER.infof("Mail %s was approved; its turn runs at the next poll of integration %s", row.id(),
                            sanitize(row.integrationId()));
                } else {
                    LOGGER.warnf("Mail %s was approved but its row was not in DONE: it is not reopened for the next poll", row.id());
                }
            } catch (RuntimeException e) {
                // Give the hold back, so the sweep settles it again instead of leaving an
                // approved mail closed and unanswered.
                reopenHeldStart(row, outcome, now);
                throw e;
            }
            return new HeldStartOutcome(outcome, Optional.empty());
        }
        try {
            ledger.transition(row.id(), State.DONE, State.DONE, null, null, outcome, false, now);
            LOGGER.infof("Mail %s held before its conversation started was settled: %s", row.id(), outcome);
            Optional<EmailIntegration> integration = directory.find(row.integrationId());
            if (integration.isEmpty() || !integration.get().settings().replies()) {
                return new HeldStartOutcome(outcome, Optional.empty());
            }
            Located located = new Located(row, integration.get().settings());
            return new HeldStartOutcome(outcome,
                    resumeRequest(located, snapshot, verdict == null ? "NONE" : verdict).flatMap(request -> enqueue(request, now)));
        } catch (RuntimeException e) {
            // The notice was not queued: keep the row on the held-start sweep, which
            // tries again (the reply key makes a second queueing a no-op).
            reopenHeldStart(row, outcome, now);
            throw e;
        }
    }

    /**
     * Puts a held start back on {@code AWAITING_START} after its settlement failed
     * part-way.
     */
    private void reopenHeldStart(LedgerEntry row, String settledAs, Instant now) {
        try {
            ledger.transitionApproval(row.id(), settledAs, EmailApprovalNotes.AWAITING_START, null, now);
        } catch (RuntimeException again) {
            LOGGER.warnf("Held mail %s was settled as %s but could not be put back for a retry: %s", row.id(), settledAs, again.getMessage());
        }
    }

    /**
     * The conversation output a resume records its verdict in
     * ({@code Conversation#resume}).
     */
    static final String HITL_DECISION_OUTPUT = "hitlDecision";

    /**
     * The conversation of a held start is gone: the row ends
     * {@link EmailApprovalNotes#CONVERSATION_GONE}, nothing is sent.
     */
    public boolean settleHeldStartGone(LedgerEntry row, Instant now) {
        if (row == null || !ledger.transitionApproval(row.id(), EmailApprovalNotes.AWAITING_START, EmailApprovalNotes.CONVERSATION_GONE, null, now)) {
            return false;
        }
        ledger.transition(row.id(), State.DONE, State.DONE, null, null, EmailApprovalNotes.CONVERSATION_GONE, false, now);
        return true;
    }

    /**
     * Whether the mail's step is one the email approval gate's rejection answered
     * before the agent ran ({@code Conversation#rejectBeforeRun}).
     */
    static boolean rejectedBeforeRun(List<? extends Map<String, Object>> outputs, String messageId) {
        int step = EmailReplyThread.mailStep(outputs, messageId);
        return step >= 0 && EmailApprovalGate.REJECTED_OUTPUT_VALUE.equals(outputs.get(step).get(EmailApprovalGate.REJECTED_OUTPUT_KEY));
    }

    /**
     * The stranded sweep found {@code inbound} submitted past its deadline with its
     * step in {@code snapshot}: makes sure the reply — or, for a paused turn, the
     * pause notice — exists. Idempotent: never writes a second row.
     *
     * @return whether this call wrote a row
     */
    public boolean ensureFor(EmailChannelSettings settings, LedgerEntry inbound, SimpleConversationMemorySnapshot snapshot, Instant now) {
        if (settings == null || !settings.replies() || snapshot == null || inbound.conversationId() == null) {
            return false;
        }
        if (snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
            String pauseId = currentPauseId(inbound.conversationId());
            if (pauseId == null) {
                return false;
            }
            Located located = new Located(inbound, settings);
            return pauseRequest(located, snapshot, pauseId).flatMap(request -> enqueue(request, now)).isPresent();
        }
        return onTurnCompleted(inbound.integrationId(), settings, inbound, inbound.conversationId(), snapshot, now).isPresent();
    }

    // --- Building requests ---------------------------------------------------

    /** The inbound mail a conversation answers and its integration's settings. */
    record Located(LedgerEntry inbound, EmailChannelSettings settings) {
    }

    /**
     * The latest inbound mail of {@code conversationId} that reached a turn, on an
     * integration that replies; empty for every other conversation.
     */
    Optional<Located> locate(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        Optional<LedgerEntry> latest;
        try {
            latest = ledger.findInboundTurn(conversationId, true);
        } catch (RuntimeException e) {
            LOGGER.debugf("Ledger rows of conversation %s could not be read: %s", sanitize(conversationId), e.getMessage());
            return Optional.empty();
        }
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Optional<EmailIntegration> integration = directory.find(latest.get().integrationId());
        if (integration.isEmpty() || !integration.get().settings().replies()) {
            return Optional.empty();
        }
        return Optional.of(new Located(latest.get(), integration.get().settings()));
    }

    private Optional<Request> pauseRequest(Located located, SimpleConversationMemorySnapshot snapshot, String pauseId) {
        LedgerEntry inbound = located.inbound();
        var outputs = snapshot.getConversationOutputs();
        int step = EmailReplyThread.mailStep(outputs, inbound.messageId());
        if (step < 0) {
            // A pause before the mail's own turn (in CONVERSATION_START): the mail was
            // not answered yet, so there is no thread step to answer from.
            return Optional.empty();
        }
        EmailReplyReview.State review = EmailReplyReview.stateOf(outputs.get(step));
        if (review == EmailReplyReview.State.PENDING) {
            // The reply itself waits for review (email plan v3 §8.2): the sender hears
            // nothing until it is decided — the notice would carry the unreviewed draft.
            return Optional.empty();
        }
        String reason = pauseReason(inbound.conversationId(), pauseId);
        // A turn armed for reply review never mails what the agent wrote before its
        // review: the notice carries no output, only that the turn waits.
        String text = pauseNotice(review == EmailReplyReview.State.NOT_ARMED ? EmailReplyThread.replyText(outputs, step) : null, reason);
        return Optional.of(new Request(inbound.integrationId(), located.settings(), inbound, inbound.conversationId(), snapshot.getUserId(),
                EmailReplyThread.mailOf(outputs.get(step)), ReplyKind.PAUSE, pauseId, text));
    }

    private Optional<Request> resumeRequest(Located located, SimpleConversationMemorySnapshot snapshot, String verdict) {
        LedgerEntry inbound = located.inbound();
        var outputs = snapshot.getConversationOutputs();
        int step = EmailReplyThread.mailStep(outputs, inbound.messageId());
        if (step < 0) {
            return Optional.empty();
        }
        String text;
        if (rejectedBeforeRun(outputs, inbound.messageId())) {
            // The target's rejectedReply, as the rejection wrote it; none configured
            // (trigger mode) means nothing is sent.
            text = EmailReplyThread.textOf(outputs.get(step));
            if (text.isBlank()) {
                return Optional.empty();
            }
        } else {
            // A turn armed for reply review never mails what the agent wrote through a
            // continuation: only its approved review sends it (as a REPLY). Read again on
            // every retry, so a row written with the neutral text stays neutral.
            String continuation = EmailReplyReview.stateOf(outputs.get(step)) == EmailReplyReview.State.NOT_ARMED
                    ? EmailReplyThread.lastText(outputs)
                    : null;
            text = resumeText(verdict, snapshot.getConversationState(), continuation);
        }
        String discriminator = verdict + "." + sha256(text).substring(0, 16);
        return Optional.of(new Request(inbound.integrationId(), located.settings(), inbound, inbound.conversationId(), snapshot.getUserId(),
                EmailReplyThread.mailOf(outputs.get(step)), ReplyKind.RESUME, discriminator, text));
    }

    static final String PAUSE_NOTICE = "This conversation is waiting for a person to approve the next step. You will get another message "
            + "when it continues.";

    /** Output so far, then the notice and the agent designer's pause reason. */
    static String pauseNotice(String outputSoFar, String reason) {
        StringBuilder text = new StringBuilder();
        if (outputSoFar != null && !outputSoFar.isBlank()) {
            text.append(outputSoFar.strip()).append("\n\n");
        }
        text.append(PAUSE_NOTICE);
        if (reason != null && !reason.isBlank()) {
            text.append("\n\nReason: ").append(reason.strip());
        }
        return text.toString();
    }

    /** What the sender is told after a decision, without naming who decided. */
    static String resumeText(String verdict, ConversationState state, String continuation) {
        if (state == ConversationState.ERROR) {
            return "The pending step was approved, but it could not be completed.";
        }
        if (HitlVerdict.REJECTED.name().equals(verdict)) {
            return "A reviewer declined the pending step, so it was not carried out.";
        }
        if (HitlVerdict.APPROVED.name().equals(verdict)) {
            return continuation == null || continuation.isBlank() ? "A reviewer approved the pending step." : continuation.strip();
        }
        return "The pending step was cancelled or timed out, so it was not carried out.";
    }

    // --- Writing and sending -------------------------------------------------

    /**
     * Writes the reply row unless one exists for this (conversation, mail, kind,
     * discriminator), and starts its first attempt.
     */
    Optional<LedgerEntry> enqueue(Request request, Instant now) {
        EmailReplyRecipients.Decision decision = EmailReplyRecipients.decide(request.integrationId(), request.settings(), request.inbound(),
                request.stepMail(), request.userId());
        if (!decision.allowed()) {
            metrics.reply("refused");
            LOGGER.infof("Email integration %s: no %s for conversation %s (%s)", sanitize(request.integrationId()), request.kind().id(),
                    sanitize(request.conversationId()), decision.refusal());
            return Optional.empty();
        }
        String key = idempotencyKey(request.conversationId(), request.inbound().id(), request.kind(), request.discriminator());
        if (ledger.findByIdempotencyKey(key).isPresent()) {
            return Optional.empty();
        }
        EmailChannelSettings settings = request.settings();
        String recipient = decision.recipient();
        String messageId = MessageIds.generate(IEmailSuppressionStore.domainOf(settings.address()));
        String sourceRef = sourceRef(request.inbound().id(), request.kind(), request.discriminator());
        String refusal = null;
        String outcome = null;
        if (suppressions.isSuppressed(request.integrationId(), recipient)) {
            refusal = SUPPRESSED_RECIPIENT;
            outcome = "suppressed";
        } else if (settings.maxRepliesPerThreadPerHour() > 0 && request.conversationId() != null
                && ledger.countByConversationSince(request.conversationId(), Kind.REPLY, now.minus(Duration.ofHours(1))) >= settings
                        .maxRepliesPerThreadPerHour()) {
            refusal = THREAD_BUDGET;
            outcome = "throttled";
        }
        if (refusal != null) {
            LedgerEntry suppressed = new LedgerEntry(UUID.randomUUID().toString(), request.integrationId(), Kind.REPLY, Direction.OUT, messageId,
                    request.conversationId(), sourceRef, key, recipient, State.SUPPRESSED, 0, null, null, null, refusal, now, now,
                    now.plus(retention));
            if (ledger.insertIfAbsent(suppressed)) {
                metrics.reply(outcome);
                LOGGER.infof("Email integration %s: %s for conversation %s not sent: %s", sanitize(request.integrationId()), request.kind().id(),
                        sanitize(request.conversationId()), refusal);
                if (refusal.equals(THREAD_BUDGET)) {
                    notifyOwners(request.integrationId(), settings, EmailOwnerNotifier.Reason.THREAD_THROTTLED, now);
                }
            }
            return Optional.empty();
        }
        Prepared prepared;
        try {
            String body = composer.body(settings, request.text(), request.stepMail());
            EmailReplyComposer.Thread thread = EmailReplyComposer.threadOf(request.stepMail(), request.inbound().messageId());
            prepared = new Prepared(settings, composer.render(settings, recipient, thread, body, messageId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            LedgerEntry failed = new LedgerEntry(UUID.randomUUID().toString(), request.integrationId(), Kind.REPLY, Direction.OUT, messageId,
                    request.conversationId(), sourceRef, key, recipient, State.FAILED, 0, null, null, null, "RENDER: " + truncate(e.getMessage()),
                    now, now, now.plus(retention));
            if (ledger.insertIfAbsent(failed)) {
                metrics.reply("failed");
                audit(failed, "failed", null, failed.lastError());
                notifyOwners(request.integrationId(), settings, EmailOwnerNotifier.Reason.REPLY_FAILED, now);
            }
            return Optional.empty();
        }
        LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), request.integrationId(), Kind.REPLY, Direction.OUT, messageId,
                request.conversationId(), sourceRef, key, recipient, State.PENDING, 1, now.plus(LEASE), UUID.randomUUID().toString(), null, null,
                now, now, now.plus(retention));
        if (!ledger.insertIfAbsent(row)) {
            return Optional.empty();
        }
        if (request.kind() == ReplyKind.GROUP) {
            groupReplies.put(row.id(), request);
        }
        try {
            executor.execute(() -> attempt(row, prepared, now));
        } catch (RejectedExecutionException e) {
            // The lease runs out and the sweep makes the attempt.
            LOGGER.debugf("Reply %s queued for the sweep: %s", row.id(), e.getMessage());
        }
        return Optional.of(row);
    }

    /**
     * One attempt of a claimed row, at {@code now} (the sweep's time, or the time
     * the row was written for its first attempt). Never throws.
     */
    void attempt(LedgerEntry row, Prepared prepared, Instant now) {
        try {
            if (suppressions.isSuppressed(row.integrationId(), row.recipient())) {
                if (ledger.complete(row.id(), row.claimToken(), State.SUPPRESSED, null, SUPPRESSED_RECIPIENT, now)) {
                    metrics.reply("suppressed");
                    groupReplies.invalidate(row.id());
                }
                return;
            }
            MailTransport.Sent sent = transport.sendMail(row.integrationId(), prepared.settings(), prepared.mail().mime(), row.recipient());
            String provider = sent.providerResponse();
            if (ledger.complete(row.id(), row.claimToken(), State.SENT, provider, null, sent.signed(), now)) {
                metrics.reply("sent", sent.signed());
                // Wall clock: the first attempt runs with the time the row was written.
                metrics.replyLatency(Duration.between(row.createdAt(), Instant.now()));
                audit(row, "sent", provider, null);
                groupReplies.invalidate(row.id());
                LOGGER.infof("Email integration %s: reply %s for conversation %s sent", sanitize(row.integrationId()), row.id(),
                        sanitize(row.conversationId()));
            }
        } catch (MailSendException e) {
            if (e.getCategory() == MailSendException.Category.SIGNING) {
                hold(row, prepared.settings(), e, now);
                return;
            }
            String error = e.getCategory() + ": " + truncate(e.getMessage());
            retry.deferOrFail(row, error, e.isPermanent(), now, () -> fail(row, prepared.settings(), error, now), () -> {
                metrics.reply("retry");
                LOGGER.infof("Email integration %s: reply %s deferred after attempt %d: %s", sanitize(row.integrationId()), row.id(),
                        row.attempts(), e.getCategory());
            });
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Email integration %s: reply %s failed unexpectedly", sanitize(row.integrationId()), row.id());
            String error = "OTHER: " + e.getClass().getSimpleName();
            retry.deferOrFail(row, error, false, now, () -> fail(row, prepared.settings(), error, now), () -> metrics.reply("retry"));
        }
    }

    /**
     * How long a reply held for its S/MIME certificate waits before it is tried
     * again.
     */
    static final Duration SMIME_HOLD = Duration.ofHours(1);

    /**
     * The shortest ledger retention the sender runs with: two holds' worth of slack
     * plus one hold, so a held row always reaches its give-up step before the
     * ledger removes it. A smaller {@code eddi.email.ledger-retention} is raised.
     */
    static final Duration MIN_RETENTION = SMIME_HOLD.multipliedBy(3);

    /**
     * How long a group reply's content stays retrievable: every retry attempt can
     * hold its lease and wait the longest backoff, and a reply held for its S/MIME
     * certificate must still be there when the hold ends.
     */
    static Duration groupReplyLifetime(int maxAttempts, Duration maxHold) {
        return LEASE.plus(LedgerRetry.MAX_BACKOFF).multipliedBy(maxAttempts).plus(maxHold);
    }

    /** The default of {@code eddi.email.smime.max-hold}. */
    public static final Duration DEFAULT_SMIME_MAX_HOLD = Duration.ofHours(72);

    /** The {@code lastError} of a reply given up after its hold. */
    public static final String SMIME_HOLD_EXPIRED = "smime_unavailable:expired";

    /**
     * The configured maximum hold, made sane: the default when unset or not
     * positive, at least one {@link #SMIME_HOLD}, and short enough that the ledger
     * retention never removes a held row before it is given up — which would end it
     * with no FAILED row, no audit entry and no notice.
     */
    static Duration saneMaxHold(Duration configured, Duration retention) {
        Duration hold = configured == null || configured.isZero() || configured.isNegative() ? DEFAULT_SMIME_MAX_HOLD : configured;
        if (hold.compareTo(SMIME_HOLD) < 0) {
            hold = SMIME_HOLD;
        }
        Duration ceiling = retention.minus(SMIME_HOLD.multipliedBy(2));
        if (ceiling.compareTo(SMIME_HOLD) < 0) {
            ceiling = SMIME_HOLD; // a retention below MIN_RETENTION; the constructor raises it, this keeps the
                                  // method total
        }
        if (hold.compareTo(ceiling) > 0) {
            LOGGER.warnf("eddi.email.smime.max-hold (%s) is not shorter than eddi.email.ledger-retention (%s); held replies are given up "
                    + "after %s instead, before retention removes them.", hold, retention, ceiling);
            hold = ceiling;
        }
        return hold;
    }

    /**
     * {@code sign: required} and the reply cannot be signed: it is never sent
     * unsigned. The row waits (without spending an attempt) and is tried again
     * hourly, so it leaves as soon as the certificate is fixed; the owners hear
     * about it once a day. A reply still unsigned {@code eddi.email.smime.max-hold}
     * (72h by default) after it was written is given up: FAILED with
     * {@value #SMIME_HOLD_EXPIRED}, audited, and the owners get a final notice. The
     * signature is always tried first, so a certificate fixed in time still sends
     * the reply, however late.
     */
    private void hold(LedgerEntry row, EmailChannelSettings settings, MailSendException e, Instant now) {
        String reason = truncate(e.getMessage() != null && e.getMessage().startsWith("smime_unavailable")
                ? e.getMessage()
                : "smime_unavailable: " + e.getMessage());
        Instant giveUpAt = row.createdAt().plus(smimeMaxHold);
        if (!now.isBefore(giveUpAt)) {
            expireHold(row, settings, reason, now);
            return;
        }
        Instant next = now.plus(SMIME_HOLD);
        if (next.isAfter(giveUpAt)) {
            next = giveUpAt;
        }
        if (ledger.hold(row.id(), row.claimToken(), next, reason, now)) {
            metrics.reply("held");
            LOGGER.warnf("Email integration %s: reply %s held, it cannot be S/MIME-signed and signing is required: %s",
                    sanitize(row.integrationId()), row.id(), reason);
            notifyOwners(row.integrationId(), settings, EmailOwnerNotifier.Reason.SMIME_UNAVAILABLE, now);
        }
    }

    /** The end of a hold: FAILED, audited, the owners told. */
    private void expireHold(LedgerEntry row, EmailChannelSettings settings, String reason, Instant now) {
        String error = truncate(SMIME_HOLD_EXPIRED + " (held " + smimeMaxHold.toHours() + "h; " + reason + ")");
        if (ledger.complete(row.id(), row.claimToken(), State.FAILED, null, error, now)) {
            metrics.reply("failed");
            audit(row, "failed", null, error);
            groupReplies.invalidate(row.id());
            LOGGER.warnf("Email integration %s: reply %s for conversation %s given up after %s held for its S/MIME signature: %s",
                    sanitize(row.integrationId()), row.id(), sanitize(row.conversationId()), smimeMaxHold, reason);
            notifyOwners(row.integrationId(), settings, EmailOwnerNotifier.Reason.SMIME_HOLD_EXPIRED, now);
        }
    }

    private void fail(LedgerEntry row, EmailChannelSettings settings, String error, Instant now) {
        if (ledger.complete(row.id(), row.claimToken(), State.FAILED, null, error, now)) {
            metrics.reply("failed");
            audit(row, "failed", null, error);
            groupReplies.invalidate(row.id());
            LOGGER.warnf("Email integration %s: reply %s for conversation %s FAILED after %d attempt(s): %s", sanitize(row.integrationId()), row.id(),
                    sanitize(row.conversationId()), row.attempts(), error);
            notifyOwners(row.integrationId(), settings, EmailOwnerNotifier.Reason.REPLY_FAILED, now);
        }
    }

    // --- Retries -------------------------------------------------------------

    @Scheduled(every = "${eddi.email.reply.sweep-interval:30s}", delayed = "45s", identity = "email-reply-sweeper",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        sweep(Instant.now());
    }

    /** Claims due reply rows and attempts each, rebuilt from the conversation. */
    int sweep(Instant now) {
        int attempted = 0;
        try {
            for (LedgerEntry row : ledger.claimDue(Kind.REPLY, now, now.plus(LEASE), SWEEP_BATCH)) {
                try {
                    retry(row, now);
                    attempted++;
                } catch (RuntimeException e) {
                    LOGGER.warnf(e, "Email reply row %s: retry failed unexpectedly", row.id());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Email reply sweep failed: %s", e.getMessage());
        }
        return attempted;
    }

    void retry(LedgerEntry row, Instant now) {
        Optional<EmailIntegration> integration = directory.find(row.integrationId());
        if (integration.isEmpty() || !integration.get().settings().replies()) {
            terminal(row, State.SUPPRESSED, NOT_CONVERSATION_MODE, "suppressed", now);
            return;
        }
        EmailChannelSettings settings = integration.get().settings();
        String[] ref = row.sourceRef() == null ? new String[0] : row.sourceRef().split("\\|", 3);
        ReplyKind kind = ref.length == 3 ? ReplyKind.fromId(ref[1]) : null;
        if (kind == null) {
            terminal(row, State.FAILED, CONTENT_UNAVAILABLE, "failed", now);
            return;
        }
        if (kind == ReplyKind.GROUP) {
            Request request = groupReplies.getIfPresent(row.id());
            if (request == null) {
                failTerminal(row, settings, CONTENT_UNAVAILABLE, now);
                return;
            }
            // What every other reply gets on a retry: the recipient is chosen again under
            // the current settings (honorReplyTo switched off since, say), a reply whose
            // recipient that changes or refuses is not sent, and the mail is rendered
            // again - with the current address as its From, which the transport also
            // uses for the envelope and the login - under the row's own Message-ID.
            EmailReplyRecipients.Decision decision = EmailReplyRecipients.decide(row.integrationId(), settings, request.inbound(),
                    request.stepMail(), request.userId());
            if (!decision.allowed() || !decision.recipient().equals(row.recipient())) {
                terminal(row, State.SUPPRESSED, RECIPIENT_REFUSED + (decision.allowed() ? "recipient_changed" : decision.refusal()), "refused", now);
                return;
            }
            RenderedMail rendered;
            try {
                String body = composer.body(settings, request.text(), request.stepMail());
                rendered = composer.render(settings, row.recipient(), EmailReplyComposer.threadOf(request.stepMail(), request.inbound().messageId()),
                        body, row.messageId());
            } catch (IllegalArgumentException | IllegalStateException e) {
                failTerminal(row, settings, "RENDER: " + truncate(e.getMessage()), now);
                return;
            }
            attempt(row, new Prepared(settings, rendered), now);
            return;
        }
        Optional<LedgerEntry> inbound = ledger.findById(ref[0]);
        SimpleConversationMemorySnapshot snapshot = row.conversationId() == null ? null : readFull(row.conversationId());
        if (inbound.isEmpty() || snapshot == null) {
            failTerminal(row, settings, CONTENT_UNAVAILABLE, now);
            return;
        }
        Located located = new Located(inbound.get(), settings);
        Optional<Request> request = switch (kind) {
            case REPLY -> replyRequest(located, snapshot, ref[2]);
            case PAUSE -> snapshot.getConversationState() == ConversationState.AWAITING_HUMAN && ref[2].equals(currentPauseId(row.conversationId()))
                    ? pauseRequest(located, snapshot, ref[2])
                    : Optional.empty();
            case RESUME -> resumeRequest(located, snapshot, ref[2].contains(".") ? ref[2].substring(0, ref[2].indexOf('.')) : ref[2]);
            default -> Optional.empty();
        };
        if (request.isEmpty()) {
            terminal(row, State.SUPPRESSED, kind == ReplyKind.PAUSE ? PAUSE_RESOLVED : CONTENT_UNAVAILABLE, "suppressed", now);
            return;
        }
        if (kind == ReplyKind.RESUME && !request.get().discriminator().equals(ref[2])) {
            // The row was written for another text than the conversation gives now (it
            // moved on, or the turn is under reply review): never send a different one.
            terminal(row, State.SUPPRESSED, CONTENT_UNAVAILABLE, "suppressed", now);
            return;
        }
        EmailReplyRecipients.Decision decision = EmailReplyRecipients.decide(row.integrationId(), settings, inbound.get(), request.get().stepMail(),
                request.get().userId());
        if (!decision.allowed() || !decision.recipient().equals(row.recipient())) {
            // The rules are applied again on every attempt: what was allowed when the row
            // was written may not be now (the settings changed).
            terminal(row, State.SUPPRESSED, RECIPIENT_REFUSED + (decision.allowed() ? "recipient_changed" : decision.refusal()), "refused", now);
            return;
        }
        RenderedMail mail;
        try {
            String body = composer.body(settings, request.get().text(), request.get().stepMail());
            mail = composer.render(settings, row.recipient(), EmailReplyComposer.threadOf(request.get().stepMail(), inbound.get().messageId()), body,
                    row.messageId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            failTerminal(row, settings, "RENDER: " + truncate(e.getMessage()), now);
            return;
        }
        attempt(row, new Prepared(settings, mail), now);
    }

    private Optional<Request> replyRequest(Located located, SimpleConversationMemorySnapshot snapshot, String stepText) {
        int step;
        try {
            step = Integer.parseInt(stepText);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        var outputs = snapshot.getConversationOutputs();
        if (step < 0 || step >= outputs.size()) {
            return Optional.empty();
        }
        Map<String, Object> mail = EmailReplyThread.mailOf(outputs.get(step));
        // Every retry asks the review again: a row is only written after an approval,
        // and what is sent is the approved text, re-read from the step.
        String text = reviewedReplyText(outputs, step);
        if (mail == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        LedgerEntry inbound = located.inbound();
        return Optional.of(new Request(inbound.integrationId(), located.settings(), inbound, inbound.conversationId(), snapshot.getUserId(), mail,
                ReplyKind.REPLY, stepText, text));
    }

    private void terminal(LedgerEntry row, State state, String reason, String outcome, Instant now) {
        if (ledger.complete(row.id(), row.claimToken(), state, null, reason, now)) {
            metrics.reply(outcome);
            groupReplies.invalidate(row.id());
        }
    }

    private void failTerminal(LedgerEntry row, EmailChannelSettings settings, String reason, Instant now) {
        fail(row, settings, reason, now);
    }

    // --- Bounces -------------------------------------------------------------

    /**
     * A delivery status notification arrived at {@code integrationId}'s mailbox.
     * When it names a reply this integration sent and reports a permanent failure
     * for that reply's recipient, the row ends {@code FAILED} and the address is
     * suppressed; a delay is noted on the row. Anything else in the report is
     * ignored: it is untrusted mail.
     *
     * @return whether the report applied to a reply
     */
    public boolean applyDeliveryReport(String integrationId, DeliveryReport report, Instant now) {
        if (report == null || report.originalMessageId() == null) {
            return false;
        }
        String original = MessageIds.parseFirst(report.originalMessageId()).orElse(report.originalMessageId().strip());
        Optional<LedgerEntry> sent = ledger.findByMessage(integrationId, Direction.OUT, original);
        if (sent.isEmpty() || sent.get().kind() != Kind.REPLY || sent.get().recipient() == null) {
            return false;
        }
        LedgerEntry row = sent.get();
        boolean applied = false;
        for (DeliveryReport.Recipient block : report.recipients()) {
            String address = block.finalRecipient() == null ? null : AddressNormalizer.tryNormalize(block.finalRecipient()).orElse(null);
            if (address == null || !address.equals(row.recipient())) {
                continue;
            }
            String status = block.status() == null ? "?" : block.status().strip();
            if (block.isPermanentFailure()) {
                if (ledger.transition(row.id(), State.SENT, State.FAILED, null, null, "BOUNCED: " + status, false, now)) {
                    metrics.reply("bounced");
                    audit(row, "bounced", null, "BOUNCED: " + status);
                }
                if (suppressions.suppress(integrationId, address, "bounce:" + status, now)) {
                    metrics.suppressed("bounce");
                    LOGGER.infof("Email integration %s: address %s suppressed after a permanent bounce (%s)", sanitize(integrationId),
                            recipientPseudonym(address), sanitize(status));
                }
                applied = true;
            } else if (block.isTransientFailure()) {
                ledger.transition(row.id(), State.SENT, State.SENT, null, null, "DELAYED: " + status, false, now);
                applied = true;
            }
        }
        return applied;
    }

    // --- Reading the conversation --------------------------------------------

    /**
     * The detailed conversation, or null when it is gone. A store that cannot be
     * read throws: a reply row's retry then lets its lease run out and tries again
     * instead of failing for good, and a reply rebuild reports failure.
     */
    private SimpleConversationMemorySnapshot readFull(String conversationId) {
        try {
            return conversationService.readConversation(conversationId, true, false, List.of());
        } catch (ResourceNotFoundException e) {
            LOGGER.debugf("Conversation %s is gone; no reply is built from it", sanitize(conversationId));
            return null;
        } catch (ResourceStoreException e) {
            throw new IllegalStateException("Could not read conversation " + conversationId + " for a reply", e);
        }
    }

    /**
     * The conversation's current pause id, or null when the conversation is gone. A
     * store that cannot be read is not "no pause": it throws, so the PAUSE row's
     * lease runs out and it is retried, and a reply rebuild reports failure,
     * instead of a live pause notice being suppressed for good.
     */
    private String currentPauseId(String conversationId) {
        try {
            ConversationMemorySnapshot stored = conversationService.getConversationMemorySnapshot(conversationId);
            return stored == null ? null : HitlDecision.pauseIdOf(stored.getHitlPausedAt());
        } catch (ResourceNotFoundException e) {
            return null;
        } catch (ResourceStoreException e) {
            throw new IllegalStateException("Could not read conversation " + conversationId + " to check its pause", e);
        }
    }

    /** The pause reason the agent designer configured, when it is this pause. */
    private String pauseReason(String conversationId, String pauseId) {
        try {
            ConversationMemorySnapshot stored = conversationService.getConversationMemorySnapshot(conversationId);
            if (stored == null || !pauseId.equals(HitlDecision.pauseIdOf(stored.getHitlPausedAt()))) {
                return null;
            }
            return stored.getHitlPauseReason();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Keys, audit ---------------------------------------------------------

    /**
     * The reply's idempotency key: conversation, the step it answers — the mail's
     * ledger row and the step's index (or the pause, or the continuation) — and the
     * kind. The mail's row is part of the step's identity because an index alone
     * repeats after an undo. A group reply has no conversation id until its
     * discussion is known, so it is keyed by the mail alone. A digest, so the key
     * holds nothing readable.
     */
    static String idempotencyKey(String conversationId, String inboundId, ReplyKind kind, String discriminator) {
        String conversation = kind == ReplyKind.GROUP ? "" : String.valueOf(conversationId);
        return "reply:" + sha256("REPLY\n" + conversation + "\n" + inboundId + "\n" + kind.id() + "\n" + discriminator);
    }

    /**
     * {@code <inbound row id>|<kind>|<discriminator>}: what a retry rebuilds from.
     */
    static String sourceRef(String inboundId, ReplyKind kind, String discriminator) {
        return inboundId + "|" + kind.id() + "|" + discriminator;
    }

    private void notifyOwners(String integrationId, EmailChannelSettings settings, EmailOwnerNotifier.Reason reason, Instant now) {
        try {
            String name = directory.find(integrationId).map(i -> i.configuration().getName()).orElse(null);
            ownerNotifier.notifyOnce(integrationId, name, settings, reason, now);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not notify the owners of email integration %s: %s", sanitize(integrationId), e.getMessage());
        }
    }

    /**
     * One audit entry per sent, failed or bounced reply: integration, kind,
     * outcome, Message-ID, ledger row, the recipient's domain and
     * {@linkplain #recipientPseudonym pseudonym} — never the address (the audit
     * ledger is write-once) and never the body.
     */
    private void audit(LedgerEntry row, String outcome, String providerResponse, String error) {
        if (auditLedger == null || !auditLedger.isResolvable()) {
            return;
        }
        try {
            AuditLedgerService service = auditLedger.get();
            if (!service.isEnabled()) {
                return;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("integrationId", row.integrationId());
            detail.put("kind", row.kind().name());
            String[] ref = row.sourceRef() == null ? new String[0] : row.sourceRef().split("\\|", 3);
            if (ref.length == 3) {
                detail.put("replyKind", ref[1]);
            }
            detail.put("outcome", outcome);
            detail.put("recipientPseudonym", recipientPseudonym(row.recipient()));
            detail.put("recipientDomain", IEmailSuppressionStore.domainOf(row.recipient()));
            detail.put("messageId", row.messageId());
            detail.put("ledgerId", row.id());
            detail.put("attempts", row.attempts());
            if (providerResponse != null) {
                detail.put("providerResponse", providerResponse);
            }
            if (error != null) {
                detail.put("error", error);
            }
            service.submit(new AuditEntry(UUID.randomUUID().toString(), row.conversationId(), null, null, null, null, -1, "email.reply", "email", -1,
                    0L, Map.of(), detail, null, null, List.of(), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not audit reply %s: %s", row.id(), e.getMessage());
        }
    }

    /**
     * The recipient as {@link GdprPseudonymizer} names it: keyed (or salted per
     * deployment), so a reader of the audit ledger or the logs cannot confirm an
     * address by hashing a list of candidates, as an unkeyed hash beside its domain
     * would allow — unless the deployment has neither a key nor a salt, where the
     * pseudonymizer falls back to an unsalted hash. The same token HITL
     * notifications and GDPR erasure write for that address.
     */
    private String recipientPseudonym(String recipient) {
        return pseudonymizer.pseudonymFor(recipient.toLowerCase(Locale.ROOT));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace('\r', ' ').replace('\n', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    /** Package-private for tests: whether a group reply is cached for retries. */
    boolean holdsGroupReply(String rowId) {
        return groupReplies.getIfPresent(rowId) != null;
    }
}
