/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.reply;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
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
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * is held in this node's memory for its retries only; on another node or after
 * a restart such a row ends {@code FAILED} ({@code CONTENT_UNAVAILABLE}).
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
    static final Duration MAX_BACKOFF = Duration.ofHours(1);
    static final int SWEEP_BATCH = 20;
    /** Ledger rows of one conversation read to find its mail. */
    public static final int CONVERSATION_ROWS = 500;
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
    private final EmailMetrics metrics;
    private final Executor executor;
    private final Duration retention;
    private final int maxAttempts;
    private final Duration retryBackoff;

    /** Rendered group replies this node may still retry, by row id. */
    private final Map<String, Prepared> groupReplies = new ConcurrentHashMap<>();

    @Inject
    public EmailReplySender(IEmailMessageLedger ledger, IEmailSuppressionStore suppressions, EmailIntegrationDirectory directory,
            IConversationService conversationService, ImapMailTransport transport, EmailReplyComposer composer, EmailOwnerNotifier ownerNotifier,
            Instance<AuditLedgerService> auditLedger, MeterRegistry meterRegistry, ManagedExecutor executor,
            @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d") Duration retention,
            @ConfigProperty(name = "eddi.email.outbound.max-attempts", defaultValue = "6") int maxAttempts,
            @ConfigProperty(name = "eddi.email.outbound.retry-backoff", defaultValue = "1m") Duration retryBackoff) {
        this(ledger, suppressions, directory, conversationService, (MailTransport) transport, composer, ownerNotifier, auditLedger, meterRegistry,
                (Executor) executor, retention, maxAttempts, retryBackoff);
    }

    /**
     * For tests: any transport, any executor (a direct one runs the attempt
     * inline).
     */
    public EmailReplySender(IEmailMessageLedger ledger, IEmailSuppressionStore suppressions, EmailIntegrationDirectory directory,
            IConversationService conversationService, MailTransport transport, EmailReplyComposer composer, EmailOwnerNotifier ownerNotifier,
            Instance<AuditLedgerService> auditLedger, MeterRegistry meterRegistry, Executor executor, Duration retention, int maxAttempts,
            Duration retryBackoff) {
        this.ledger = ledger;
        this.suppressions = suppressions;
        this.directory = directory;
        this.conversationService = conversationService;
        this.transport = transport;
        this.composer = composer;
        this.ownerNotifier = ownerNotifier;
        this.auditLedger = auditLedger;
        this.metrics = new EmailMetrics(meterRegistry);
        this.executor = executor;
        this.retention = retention == null || retention.isZero() || retention.isNegative() ? Duration.ofDays(30) : retention;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative() ? Duration.ofMinutes(1) : retryBackoff;
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
        String text = EmailReplyThread.replyText(outputs, step);
        if (text.isBlank()) {
            metrics.reply("no_output");
            return Optional.empty();
        }
        return enqueue(new Request(integrationId, settings, inbound, conversationId, snapshot.getUserId(),
                EmailReplyThread.mailOf(outputs.get(step)), ReplyKind.REPLY, Integer.toString(step), text), now);
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
        Optional<Located> located = locate(conversationId);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        SimpleConversationMemorySnapshot snapshot = readFull(conversationId);
        if (snapshot == null) {
            return Optional.empty();
        }
        return resumeRequest(located.get(), snapshot, verdict == null ? "NONE" : verdict.name()).flatMap(request -> enqueue(request, now));
    }

    /**
     * The stranded sweep found {@code inbound} submitted past its deadline with its
     * step in {@code snapshot}: makes sure the reply — or, for a paused turn, the
     * pause notice — exists. Idempotent; never sends twice.
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
        List<LedgerEntry> rows;
        try {
            rows = ledger.findByConversation(conversationId, CONVERSATION_ROWS);
        } catch (RuntimeException e) {
            LOGGER.debugf("Ledger rows of conversation %s could not be read: %s", sanitize(conversationId), e.getMessage());
            return Optional.empty();
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            LedgerEntry row = rows.get(i);
            if (row.kind() == Kind.TRIGGER && row.direction() == Direction.IN
                    && (row.state() == State.TURN_SUBMITTED || row.state() == State.DONE)) {
                Optional<EmailIntegration> integration = directory.find(row.integrationId());
                if (integration.isEmpty() || !integration.get().settings().replies()) {
                    return Optional.empty();
                }
                return Optional.of(new Located(row, integration.get().settings()));
            }
        }
        return Optional.empty();
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
        String reason = pauseReason(inbound.conversationId(), pauseId);
        String text = pauseNotice(EmailReplyThread.replyText(outputs, step), reason);
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
        String text = resumeText(verdict, snapshot.getConversationState(), EmailReplyThread.lastText(outputs));
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
     * Writes the reply row unless one exists for this (conversation, kind,
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
            if (groupReplies.size() >= MAX_CACHED_GROUP_REPLIES) {
                groupReplies.clear();
            }
            groupReplies.put(row.id(), prepared);
        }
        try {
            executor.execute(() -> attempt(row, prepared));
        } catch (RejectedExecutionException e) {
            // The lease runs out and the sweep makes the attempt.
            LOGGER.debugf("Reply %s queued for the sweep: %s", row.id(), e.getMessage());
        }
        return Optional.of(row);
    }

    /** One attempt of a claimed row. Never throws. */
    void attempt(LedgerEntry row, Prepared prepared) {
        Instant now = Instant.now();
        try {
            if (suppressions.isSuppressed(row.integrationId(), row.recipient())) {
                if (ledger.complete(row.id(), row.claimToken(), State.SUPPRESSED, null, SUPPRESSED_RECIPIENT, now)) {
                    metrics.reply("suppressed");
                    groupReplies.remove(row.id());
                }
                return;
            }
            String provider = transport.send(row.integrationId(), prepared.settings(), prepared.mail().mime(), row.recipient());
            if (ledger.complete(row.id(), row.claimToken(), State.SENT, provider, null, now)) {
                metrics.reply("sent");
                metrics.replyLatency(Duration.between(row.createdAt(), now));
                audit(row, "sent", provider, null);
                groupReplies.remove(row.id());
                LOGGER.infof("Email integration %s: reply %s for conversation %s sent", sanitize(row.integrationId()), row.id(),
                        sanitize(row.conversationId()));
            }
        } catch (MailSendException e) {
            String error = e.getCategory() + ": " + truncate(e.getMessage());
            if (e.isPermanent() || row.attempts() >= maxAttempts) {
                fail(row, prepared.settings(), error, now);
            } else if (ledger.reschedule(row.id(), row.claimToken(), now.plus(backoff(row.attempts())), error, now)) {
                metrics.reply("retry");
                LOGGER.infof("Email integration %s: reply %s deferred after attempt %d: %s", sanitize(row.integrationId()), row.id(),
                        row.attempts(), e.getCategory());
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Email integration %s: reply %s failed unexpectedly", sanitize(row.integrationId()), row.id());
            if (row.attempts() >= maxAttempts) {
                fail(row, prepared.settings(), "OTHER: " + e.getClass().getSimpleName(), now);
            } else {
                ledger.reschedule(row.id(), row.claimToken(), now.plus(backoff(row.attempts())), "OTHER: " + e.getClass().getSimpleName(), now);
            }
        }
    }

    private void fail(LedgerEntry row, EmailChannelSettings settings, String error, Instant now) {
        if (ledger.complete(row.id(), row.claimToken(), State.FAILED, null, error, now)) {
            metrics.reply("failed");
            audit(row, "failed", null, error);
            groupReplies.remove(row.id());
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
            Prepared cached = groupReplies.get(row.id());
            if (cached == null) {
                failTerminal(row, settings, CONTENT_UNAVAILABLE, now);
                return;
            }
            attempt(row, new Prepared(settings, cached.mail()));
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
        attempt(row, new Prepared(settings, mail));
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
        String text = EmailReplyThread.replyText(outputs, step);
        if (mail == null || text.isBlank()) {
            return Optional.empty();
        }
        LedgerEntry inbound = located.inbound();
        return Optional.of(new Request(inbound.integrationId(), located.settings(), inbound, inbound.conversationId(), snapshot.getUserId(), mail,
                ReplyKind.REPLY, stepText, text));
    }

    private void terminal(LedgerEntry row, State state, String reason, String outcome, Instant now) {
        if (ledger.complete(row.id(), row.claimToken(), state, null, reason, now)) {
            metrics.reply(outcome);
            groupReplies.remove(row.id());
        }
    }

    private void failTerminal(LedgerEntry row, EmailChannelSettings settings, String reason, Instant now) {
        fail(row, settings, reason, now);
    }

    /** {@code retry-backoff × 2^(attempts-1)}, capped at {@link #MAX_BACKOFF}. */
    Duration backoff(int attempts) {
        int doublings = Math.min(Math.max(0, attempts - 1), 20);
        Duration delay = retryBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
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
                            IEmailSuppressionStore.hash(address), sanitize(status));
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

    private SimpleConversationMemorySnapshot readFull(String conversationId) {
        try {
            return conversationService.readConversation(conversationId, true, false, List.of());
        } catch (Exception e) {
            LOGGER.debugf("Conversation %s could not be read for a reply: %s", sanitize(conversationId), e.getMessage());
            return null;
        }
    }

    private String currentPauseId(String conversationId) {
        try {
            ConversationMemorySnapshot stored = conversationService.getConversationMemorySnapshot(conversationId);
            return stored == null ? null : HitlDecision.pauseIdOf(stored.getHitlPausedAt());
        } catch (Exception e) {
            return null;
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
     * outcome, Message-ID, ledger row, the recipient's domain and SHA-256 — never
     * the address (the audit ledger is write-once) and never the body.
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
            detail.put("recipientHash", IEmailSuppressionStore.hash(row.recipient()));
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
        return groupReplies.containsKey(rowId);
    }
}
