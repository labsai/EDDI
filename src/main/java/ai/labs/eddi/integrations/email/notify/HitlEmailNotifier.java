/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.notify;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.hitl.model.HitlNotifyConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.MessageIds;
import ai.labs.eddi.engine.mail.NotifyRecipientPolicy;
import ai.labs.eddi.engine.mail.OutboundMail;
import ai.labs.eddi.engine.mail.PlainTextMailRenderer;
import ai.labs.eddi.engine.mail.SystemMailer;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Emails the people an agent names when one of its conversations pauses for
 * approval (email plan §16, Phase 1) — {@code hitlConfig.notify.email}.
 * <p>
 * <b>Trigger.</b> {@link HitlPauseCommittedEvent}, fired after a pause is
 * durable. Nothing polls conversations.
 * <p>
 * <b>Exactly once per pause and recipient, cluster-wide.</b> For each recipient
 * a ledger row is inserted under an idempotency key derived from kind,
 * conversation, pause and recipient; only the pod whose insert wins sends. The
 * winning row is inserted already claimed, with a lease, and the first attempt
 * happens at once. A failure that may be transient is retried by
 * {@link #sweep()} with exponential backoff
 * ({@code eddi.email.outbound.retry-backoff}, doubling, capped at one hour)
 * until {@code eddi.email.outbound.max-attempts}; a permanent refusal fails the
 * row immediately.
 * <p>
 * <b>Nothing stored that could be read.</b> The ledger holds no body: every
 * attempt re-reads the pause from the conversation store and re-renders the
 * mail, with the Message-ID the row was given. A pause that has been decided
 * meanwhile is not mailed — the row ends {@code SUPPRESSED}.
 * <p>
 * Every terminal outcome is counted ({@code eddi.email.outbound}) and every
 * sent or failed notification audited (kind, conversation, a hash and the
 * domain of the recipient, Message-ID — never the body).
 */
@ApplicationScoped
public class HitlEmailNotifier {

    private static final Logger LOGGER = Logger.getLogger(HitlEmailNotifier.class);

    /** How long a claim holds a row: covers connect + submission timeouts. */
    static final Duration LEASE = Duration.ofMinutes(5);

    /** The longest a retry waits. */
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** Rows one sweep attempts. */
    static final int SWEEP_BATCH = 20;

    static final String METER_OUTBOUND = "eddi.email.outbound";
    static final String METER_PENDING = "eddi.email.ledger.pending";

    static final String APPROVALS_PATH = "/manage/approvals";

    /**
     * Why a row was not sent: its recipient is outside the operator's domain
     * policy.
     */
    static final String NOT_ALLOWED = "RECIPIENT_NOT_ALLOWED: outside " + NotifyRecipientPolicy.ALLOWED_DOMAINS;

    /** Why a row was not sent: the recipient had its hourly maximum already. */
    static final String RATE_LIMITED = "RATE_LIMITED: eddi.mail.notify.max-per-recipient-per-hour reached";

    /** Why a row ended without being sent because its pause is gone. */
    static final String PAUSE_RESOLVED = "PAUSE_RESOLVED: the pause was decided, cancelled or replaced before the notification went out";

    private final SystemMailer mailer;
    private final IEmailMessageLedger ledger;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IAgentStore agentStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final Instance<ConnectionsConfig> connectionsConfig;
    private final AuditLedgerService auditLedgerService;
    private final GdprPseudonymizer pseudonymizer;
    private final MeterRegistry meterRegistry;
    private final Duration retention;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final NotifyRecipientPolicy recipientPolicy;
    private final String recipientPolicyError;
    private final int maxPerRecipientPerHour;

    /**
     * recipient pseudonym to when this pod last warned that it was rate-limited.
     */
    private final Map<String, Instant> rateLimitWarned = new ConcurrentHashMap<>();

    private final AtomicReference<Double> pending = new AtomicReference<>(Double.NaN);

    @Inject
    public HitlEmailNotifier(SystemMailer mailer, IEmailMessageLedger ledger, IConversationMemoryStore conversationMemoryStore,
            IAgentStore agentStore, IDocumentDescriptorStore documentDescriptorStore, Instance<ConnectionsConfig> connectionsConfig,
            AuditLedgerService auditLedgerService, GdprPseudonymizer pseudonymizer, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d") Duration retention,
            @ConfigProperty(name = "eddi.email.outbound.max-attempts", defaultValue = "6") int maxAttempts,
            @ConfigProperty(name = "eddi.email.outbound.retry-backoff", defaultValue = "1m") Duration retryBackoff,
            @ConfigProperty(name = "eddi.mail.notify.allowed-recipient-domains") Optional<List<String>> allowedRecipientDomains,
            @ConfigProperty(name = "eddi.mail.notify.max-per-recipient-per-hour", defaultValue = "30") int maxPerRecipientPerHour) {
        this.mailer = mailer;
        this.ledger = ledger;
        this.conversationMemoryStore = conversationMemoryStore;
        this.agentStore = agentStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.connectionsConfig = connectionsConfig;
        this.auditLedgerService = auditLedgerService;
        this.pseudonymizer = pseudonymizer;
        this.meterRegistry = meterRegistry;
        this.retention = retention == null || retention.isZero() || retention.isNegative() ? Duration.ofDays(30) : retention;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative() ? Duration.ofMinutes(1) : retryBackoff;
        this.maxPerRecipientPerHour = maxPerRecipientPerHour;
        NotifyRecipientPolicy policy;
        String error = null;
        try {
            policy = NotifyRecipientPolicy.of(allowedRecipientDomains == null ? List.of() : allowedRecipientDomains.orElse(List.of()),
                    mailer.from().map(MailAddress::address).orElse(null));
        } catch (IllegalArgumentException e) {
            // Fail closed: a typo in the allowlist must not widen it to everyone.
            policy = NotifyRecipientPolicy.of(List.of(), null);
            error = e.getMessage();
        }
        this.recipientPolicy = policy;
        this.recipientPolicyError = error;
    }

    @PostConstruct
    void registerGauge() {
        Gauge.builder(METER_PENDING, pending, AtomicReference::get)
                .description("Email ledger rows not yet sent, failed or suppressed (store-wide; NaN until the first sweep)").register(meterRegistry);
        for (String outcome : List.of("sent", "retry", "failed", "suppressed", "not_allowed", "rate_limited")) {
            outbound(outcome);
        }
        if (recipientPolicyError != null) {
            LOGGER.errorf("HITL email notifications are sent to NOBODY: %s", recipientPolicyError);
        } else if (mailer.isEnabled()) {
            LOGGER.infof("HITL email notifications may go to %s", recipientPolicy.describe());
        }
    }

    private void count(String outcome) {
        outbound(outcome).increment();
    }

    private Counter outbound(String outcome) {
        return meterRegistry.counter(METER_OUTBOUND, "kind", "notify", "outcome", outcome);
    }

    // --- The trigger ---------------------------------------------------------

    void onPauseCommitted(@ObservesAsync HitlPauseCommittedEvent event) {
        try {
            notifyPause(event.conversationId(), event.pauseId(), Instant.now());
        } catch (RuntimeException e) {
            // Best-effort by contract: the pause is persisted and must not care.
            LOGGER.warnf(e, "HITL email notification for conversation %s failed", sanitize(event.conversationId()));
        }
    }

    /**
     * Inserts one ledger row per configured recipient of the paused agent and makes
     * the first attempt for each row this pod won.
     */
    void notifyPause(String conversationId, String pauseId, Instant now) {
        if (!mailer.isEnabled() || conversationId == null || pauseId == null) {
            return;
        }
        Optional<PauseContext> context = loadPause(conversationId, pauseId);
        if (context.isEmpty()) {
            return;
        }
        List<String> recipients = recipients(context.get().agent());
        if (recipients.isEmpty()) {
            return;
        }
        MailAddress from = mailer.from().orElseThrow();
        for (String recipient : recipients) {
            try {
                notifyRecipient(conversationId, pauseId, recipient, from, context.get(), now);
            } catch (RuntimeException e) {
                // One recipient's failure (a store error, say) must not leave the rest
                // without a row: the sweep retries rows, it never creates them. A row
                // this recipient already got stays PENDING under its lease.
                LOGGER.warnf("HITL notification to recipient %s for conversation %s failed: %s", recipientPseudonym(recipient),
                        sanitize(conversationId), e.getMessage());
            }
        }
    }

    /**
     * One recipient of a pause: a SUPPRESSED row, or a PENDING row and its first
     * attempt.
     */
    private void notifyRecipient(String conversationId, String pauseId, String recipient, MailAddress from, PauseContext context, Instant now) {
        String refusal = refusal(recipient, now);
        if (refusal != null) {
            LedgerEntry suppressed = new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY,
                    Direction.OUT, MessageIds.generate(from.domain()), conversationId, pauseId,
                    idempotencyKey(conversationId, pauseId, recipient), recipient, State.SUPPRESSED, 0, null, null, null, refusal, now, now,
                    now.plus(retention));
            if (ledger.insertIfAbsent(suppressed)) {
                boolean rateLimited = RATE_LIMITED.equals(refusal);
                count(rateLimited ? "rate_limited" : "not_allowed");
                if (rateLimited) {
                    warnRateLimited(recipient, now);
                }
            }
            return;
        }
        LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY, Direction.OUT,
                MessageIds.generate(from.domain()), conversationId, pauseId, idempotencyKey(conversationId, pauseId, recipient), recipient,
                State.PENDING, 1, now.plus(LEASE), UUID.randomUUID().toString(), null, null, now, now, now.plus(retention));
        if (ledger.insertIfAbsent(row)) {
            attempt(row, context, now);
        }
    }

    /**
     * Why {@code recipient} must not be mailed now (outside the domain policy, or
     * over its hourly maximum, counted from the ledger so the limit holds across
     * the cluster), or null when it may be. The count and the insert are two steps,
     * so concurrent pauses on several pods can overshoot the limit by a few; it is
     * a flood brake, not an exact quota.
     */
    String refusal(String recipient, Instant now) {
        if (!recipientPolicy.allows(recipient)) {
            return NOT_ALLOWED;
        }
        if (maxPerRecipientPerHour > 0
                && ledger.countCreatedSince(recipient, Kind.NOTIFY, now.minus(Duration.ofHours(1))) >= maxPerRecipientPerHour) {
            return RATE_LIMITED;
        }
        return null;
    }

    /**
     * One WARN per recipient per hour on this pod; the address only as a pseudonym.
     */
    private void warnRateLimited(String recipient, Instant now) {
        String key = recipientPseudonym(recipient);
        Instant last = rateLimitWarned.get(key);
        if (last != null && last.isAfter(now.minus(Duration.ofHours(1)))) {
            return;
        }
        if (rateLimitWarned.size() > 10_000) {
            rateLimitWarned.clear();
        }
        rateLimitWarned.put(key, now);
        LOGGER.warnf("HITL email notifications to recipient %s are rate-limited: %d in the last hour "
                + "(eddi.mail.notify.max-per-recipient-per-hour). Further notifications are suppressed until the hour has passed.", key,
                maxPerRecipientPerHour);
    }

    /**
     * Package-private for tests: whether a rate-limit WARN was logged for
     * {@code recipient}.
     */
    boolean warnedRateLimited(String recipient) {
        return rateLimitWarned.containsKey(recipientPseudonym(recipient));
    }

    /**
     * The agent version's configured recipients, normalised; invalid ones skipped.
     */
    private static List<String> recipients(AgentConfiguration agent) {
        if (agent == null || agent.getHitlConfig() == null || agent.getHitlConfig().getNotify() == null) {
            return List.of();
        }
        HitlNotifyConfig.Email email = agent.getHitlConfig().getNotify().getEmail();
        if (email == null || email.getRecipients() == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        int index = 0;
        for (String recipient : email.getRecipients()) {
            try {
                normalized.add(HitlConfigValidation.normalizeRecipient(recipient, "hitlConfig.notify.email.recipients[" + index + "]"));
            } catch (IllegalArgumentException e) {
                // Saved before validation existed, or written straight to the store.
                LOGGER.warnf("Skipping a HITL notification recipient: %s", e.getMessage());
            }
            index++;
            if (normalized.size() >= HitlConfigValidation.MAX_NOTIFY_RECIPIENTS) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    /** The idempotency key: a digest, so the key itself holds no address. */
    static String idempotencyKey(String conversationId, String pauseId, String recipient) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("NOTIFY\n" + conversationId + "\n" + pauseId + "\n" + recipient).getBytes(StandardCharsets.UTF_8));
            return "notify:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // --- Retries -------------------------------------------------------------

    @Scheduled(every = "${eddi.email.ledger.sweep-interval:30s}", delayed = "30s", identity = "email-ledger-sweeper",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        sweep(Instant.now());
    }

    /** One sweep at {@code now}: due retries, then retention, then the gauge. */
    void sweep(Instant now) {
        try {
            if (mailer.isEnabled()) {
                for (LedgerEntry claimed : ledger.claimDue(Kind.NOTIFY, now, now.plus(LEASE), SWEEP_BATCH)) {
                    try {
                        attempt(claimed, null, now);
                    } catch (RuntimeException e) {
                        LOGGER.warnf(e, "Email ledger row %s: attempt failed unexpectedly", claimed.id());
                    }
                }
            }
            long expired = ledger.deleteExpired(now);
            if (expired > 0) {
                LOGGER.debugf("Removed %d expired email ledger rows", expired);
            }
            pending.set((double) ledger.countPending());
        } catch (RuntimeException e) {
            pending.set(Double.NaN);
            LOGGER.warnf("Email ledger sweep failed: %s", e.getMessage());
        }
    }

    // --- One attempt ---------------------------------------------------------

    /**
     * Sends one claimed row. {@code known} is the pause context when the caller
     * just read it (the first attempt); retries read it again.
     */
    void attempt(LedgerEntry row, PauseContext known, Instant now) {
        Optional<PauseContext> context;
        try {
            context = known != null ? Optional.of(known) : loadPause(row.conversationId(), row.sourceRef());
        } catch (IllegalStateException e) {
            // The store could not answer: retried with the send backoff, bounded by the
            // same attempt limit, never suppressed as a resolved pause.
            String error = "UNREADABLE: " + truncate(e.getMessage(), 300);
            if (row.attempts() >= maxAttempts) {
                if (ledger.complete(row.id(), row.claimToken(), State.FAILED, null, error, now)) {
                    count("failed");
                    LOGGER.warnf("HITL notification %s for conversation %s FAILED after %d attempt(s): its pause could not be read", row.id(),
                            sanitize(row.conversationId()), row.attempts());
                }
            } else if (ledger.reschedule(row.id(), row.claimToken(), now.plus(backoff(row.attempts())), error, now)) {
                count("retry");
            }
            return;
        }
        if (context.isEmpty()) {
            if (ledger.complete(row.id(), row.claimToken(), State.SUPPRESSED, null, PAUSE_RESOLVED, now)) {
                count("suppressed");
            }
            return;
        }
        if (!recipientPolicy.allows(row.recipient())) {
            // The policy can narrow between attempts (a restart with new settings).
            if (ledger.complete(row.id(), row.claimToken(), State.SUPPRESSED, null, NOT_ALLOWED, now)) {
                count("not_allowed");
            }
            return;
        }
        PlainTextMailRenderer.RenderedMail rendered;
        MailAddress recipient = new MailAddress(row.recipient(), null);
        try {
            HitlNotificationContent.Message message = HitlNotificationContent.render(context.get().pause(), approvalsUrl());
            rendered = PlainTextMailRenderer.render(new OutboundMail(mailer.from().orElseThrow(), List.of(recipient), List.of(), message.subject(),
                    message.text(), row.messageId(), null, null, OutboundMail.Kind.AUTO_GENERATED, now));
        } catch (RuntimeException e) {
            // Any render failure is terminal for this row: escaping here would leave it
            // PENDING, reclaimed after every lease until retention removed it.
            fail(row, context.get(), "RENDER: " + e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 300), now);
            return;
        }
        try {
            SystemMailer.SendResult result = mailer.send(rendered, List.of(recipient));
            if (ledger.complete(row.id(), row.claimToken(), State.SENT, result.serverResponse(), null, now)) {
                count("sent");
                audit(row, context.get(), "sent", result.serverResponse(), null);
            }
        } catch (MailSendException e) {
            String error = e.getCategory() + ": " + truncate(e.getMessage(), 300);
            if (e.isPermanent() || row.attempts() >= maxAttempts) {
                fail(row, context.get(), error, now);
            } else if (ledger.reschedule(row.id(), row.claimToken(), now.plus(backoff(row.attempts())), error, now)) {
                count("retry");
                LOGGER.infof("HITL notification %s for conversation %s deferred after attempt %d: %s", row.id(), sanitize(row.conversationId()),
                        row.attempts(), e.getCategory());
            }
        } catch (RuntimeException e) {
            // Not a categorised SMTP failure (a bad address, a mailer that could not be
            // built): still an attempt. Escaping here would leave the row PENDING,
            // reclaimed after every lease without ever reaching max-attempts.
            String error = "SEND: " + e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 300);
            if (row.attempts() >= maxAttempts) {
                fail(row, context.get(), error, now);
            } else if (ledger.reschedule(row.id(), row.claimToken(), now.plus(backoff(row.attempts())), error, now)) {
                count("retry");
            }
        }
    }

    private void fail(LedgerEntry row, PauseContext context, String error, Instant now) {
        if (ledger.complete(row.id(), row.claimToken(), State.FAILED, null, error, now)) {
            count("failed");
            audit(row, context, "failed", null, error);
            LOGGER.warnf("HITL notification %s for conversation %s FAILED after %d attempt(s): %s", row.id(), sanitize(row.conversationId()),
                    row.attempts(), error);
        }
    }

    /** {@code retry-backoff × 2^(attempts-1)}, capped at {@link #MAX_BACKOFF}. */
    Duration backoff(int attempts) {
        int doublings = Math.min(Math.max(0, attempts - 1), 20);
        Duration delay = retryBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /**
     * The Manager approvals page, or null when no usable public base URL is set.
     */
    String approvalsUrl() {
        if (connectionsConfig == null || !connectionsConfig.isResolvable()) {
            return null;
        }
        ConnectionsConfig config = connectionsConfig.get();
        String base = config.getPublicBaseUrl();
        if (base == null || base.isBlank() || config.publicBaseUrlProblem().isPresent()) {
            return null;
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + APPROVALS_PATH;
    }

    // --- Reading the pause ---------------------------------------------------

    /** What an attempt needs: the pause facts and the agent version that paused. */
    record PauseContext(HitlNotificationContent.Pause pause, AgentConfiguration agent, ConversationMemorySnapshot snapshot) {
    }

    /**
     * The pause, when the conversation is still in exactly that pause; empty when
     * it was decided, cancelled, replaced or deleted.
     */
    Optional<PauseContext> loadPause(String conversationId, String pauseId) {
        try {
            if (conversationMemoryStore.getConversationState(conversationId) != ConversationState.AWAITING_HUMAN) {
                return Optional.empty();
            }
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (snapshot == null || !pauseId.equals(HitlDecision.pauseIdOf(snapshot.getHitlPausedAt()))) {
                return Optional.empty();
            }
            AgentConfiguration agent = snapshot.getAgentId() == null ? null : agentStore.read(snapshot.getAgentId(), snapshot.getAgentVersion());
            String agentName = null;
            try {
                var descriptor = documentDescriptorStore.readDescriptor(snapshot.getAgentId(), snapshot.getAgentVersion());
                agentName = descriptor == null ? null : descriptor.getName();
            } catch (Exception e) {
                LOGGER.debugf("No descriptor for agent %s: %s", sanitize(snapshot.getAgentId()), e.getMessage());
            }
            var pause = new HitlNotificationContent.Pause(agentName, snapshot.getAgentId(), snapshot.getHitlPauseType(),
                    snapshot.getHitlPauseReason(),
                    snapshot.getHitlPausedAt(), snapshot.getHitlTimeoutPolicy(), snapshot.getHitlApprovalTimeout(), conversationId,
                    snapshot.getHitlPendingToolCalls());
            return Optional.of(new PauseContext(pause, agent, snapshot));
        } catch (IResourceStore.ResourceNotFoundException e) {
            // A missing conversation or agent is a pause nobody can act on any more.
            LOGGER.debugf("Pause %s of conversation %s is gone: %s", pauseId, sanitize(conversationId), e.getMessage());
            return Optional.empty();
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            // Not knowing is not "resolved": a retry that took this for a decided pause
            // would suppress a live notice for good.
            throw new IllegalStateException("Pause " + pauseId + " of conversation " + conversationId + " could not be read", e);
        }
    }

    // --- Audit ---------------------------------------------------------------

    private void audit(LedgerEntry row, PauseContext context, String outcome, String providerResponse, String error) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("kind", row.kind().name());
            detail.put("outcome", outcome);
            detail.put("recipientPseudonym", recipientPseudonym(row.recipient()));
            detail.put("recipientDomain", row.recipient().substring(row.recipient().lastIndexOf('@') + 1));
            detail.put("messageId", row.messageId());
            detail.put("ledgerId", row.id());
            detail.put("attempts", row.attempts());
            if (providerResponse != null) {
                detail.put("providerResponse", providerResponse);
            }
            if (error != null) {
                detail.put("error", error);
            }
            ConversationMemorySnapshot snapshot = context.snapshot();
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), row.conversationId(), snapshot.getAgentId(),
                    snapshot.getAgentVersion(), null, snapshot.getEnvironment() == null ? null : snapshot.getEnvironment().toString(), -1,
                    "email.notify", "email", -1, 0L, Map.of(), detail, null, null, List.of(), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.warnf("Failed to audit HITL notification %s: %s", row.id(), e.getMessage());
        }
    }

    /**
     * The keyed pseudonym of the normalised address ({@link GdprPseudonymizer}).
     * The audit ledger is write-once and GDPR erasure cannot reach it, so it names
     * the recipient in a form an operator holding the deployment's key can match
     * ("was alice@… notified?"), but that a reader of the ledger or the logs cannot
     * confirm by hashing a list of candidate addresses — which an unkeyed hash
     * beside the recipient's domain would allow. It is the same token GDPR erasure
     * writes for that identifier, so both name one person alike.
     */
    String recipientPseudonym(String recipient) {
        return pseudonymizer.pseudonymFor(recipient.toLowerCase(Locale.ROOT));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** Package-private for tests: the gauge's last value. */
    double pendingGauge() {
        return pending.get();
    }
}
