/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.AgentNotReadyException;
import ai.labs.eddi.engine.api.IConversationService.ConversationAwaitingApprovalException;
import ai.labs.eddi.engine.api.IConversationService.ConversationEndedException;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.engine.mail.InboundMail;
import ai.labs.eddi.engine.mail.MailAddress;
import ai.labs.eddi.engine.mail.MailAttachment;
import ai.labs.eddi.engine.mail.MailAuthentication;
import ai.labs.eddi.engine.mail.MailLimits;
import ai.labs.eddi.engine.mail.MailParseException;
import ai.labs.eddi.engine.mail.MessageIds;
import ai.labs.eddi.engine.mail.MimeMessageParser;
import ai.labs.eddi.engine.mail.SenderAuthenticator;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.email.EmailOwnerNotifier.Reason;
import ai.labs.eddi.integrations.email.ledger.IEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxMessage;
import ai.labs.eddi.integrations.email.transport.MailboxException;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Turns one polled mail into (at most) one agent turn, in trigger mode (email
 * plan §6, §9, §11–§14). Nothing here ever sends mail to a sender.
 * <p>
 * <b>The order is the security model.</b>
 * <ol>
 * <li>The ledger row is written ({@code RECEIVED}, a conditional insert on
 * {@code (integration, IN, Message-ID)}) before anything else happens to the
 * mail, so a re-poll, a second node or a restart sees it. The mailbox is marked
 * processed only after this method returns {@link Disposition#SETTLED}, that
 * is, after the row is written.</li>
 * <li>Sender authentication (DKIM + DMARC alignment). A {@code temporary}
 * verdict (DNS trouble) defers the mail — it stays unmarked and is retried —
 * rather than treating the sender as unauthenticated.</li>
 * <li>Loop guard, blocklist (the integration's own address always), allowlist,
 * then {@code requireAuthentication}: refusals are recorded ({@code REFUSED}
 * with the reason), counted, and never answered.</li>
 * <li>Budgets: the integration's daily cost budget, the per-sender daily turns,
 * tenant quotas. Over a limit the mail stays in the mailbox for later and the
 * owners hear about it once a day ({@link EmailOwnerNotifier}).</li>
 * <li>Identity (§9): authenticated → {@code email:<id>:<address>} or
 * {@code email:<id>}; unauthenticated (only with
 * {@code requireAuthentication: false}) → a one-off {@code email-anon:} id with
 * no memory, never correlated with anything.</li>
 * <li>The claim {@code RECEIVED → CLAIMED} (conditional). Nothing has left EDDI
 * yet, so a claim whose node died is given back after its deadline and the mail
 * — still unmarked in the polled folder, the cursor held before it — is fetched
 * and processed again. Then the point of no return
 * {@code CLAIMED → TURN_SUBMITTED}, immediately before the conversation is
 * started (its {@code CONVERSATION_START} turn may call tools) or the turn is
 * submitted. A crash after that never runs the mail a second time: a re-poll
 * only marks it, and the stranded sweep resolves the row
 * ({@link EmailInboundSweeper}).</li>
 * </ol>
 * <b>Mail is data.</b> The turn's input is the author's {@code inputTemplate}
 * rendered by the restricted runtime engine with the mail as its data; nothing
 * from the mail is ever parsed as a template.
 */
@ApplicationScoped
public class EmailInboundProcessor {

    private static final Logger LOGGER = Logger.getLogger(EmailInboundProcessor.class);

    /** Context key of the mail map. */
    public static final String MAIL_CONTEXT_KEY = "mail";
    /** Context key naming the integration. */
    public static final String INTEGRATION_CONTEXT_KEY = "emailIntegration";

    /** What the poller does with the message next. */
    public enum Disposition {
        /** Recorded for good (done, refused, or claimed): mark it processed. */
        SETTLED,
        /** Leave it unmarked; a later poll retries it. */
        DEFERRED,
        /** Leave it unmarked and stop this poll's batch (a budget or quota is hit). */
        STOP
    }

    /** Everything the processor needs about the integration a mail arrived on. */
    public record IntegrationContext(String integrationId, ChannelIntegrationConfiguration integration, EmailChannelSettings settings,
            Set<String> otherIntegrationAddresses) {
    }

    /** Attachments stored per mail at most. */
    static final int MAX_STORED_ATTACHMENTS = 20;

    /** Claims before a mail whose turn keeps failing to start is refused. */
    static final int MAX_ATTEMPTS = 5;

    /** How long a temporary authentication result may defer a mail. */
    static final Duration MAX_TEMPORARY_DEFERRAL = Duration.ofDays(1);

    static final Duration RETRY_SHORT = Duration.ofMinutes(5);
    static final Duration RETRY_BUDGET = Duration.ofMinutes(15);

    private final IEmailMessageLedger ledger;
    private final IEmailMailboxStateStore stateStore;
    private final MimeMessageParser parser;
    private final SenderAuthenticator authenticator;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final IUserConversationStore userConversationStore;
    private final ITemplatingEngine templatingEngine;
    private final Instance<IAttachmentStore> attachmentStore;
    private final EmailOwnerNotifier ownerNotifier;

    /**
     * Records refused mail by reason category (email plan §17). Field-injected so
     * the constructor stays the one the tests use; null there, and then nothing is
     * audited.
     */
    @Inject
    Instance<AuditLedgerService> auditLedger;
    private final EmailMetrics metrics;
    private final Duration strandedAfter;
    private final Duration retention;

    @Inject
    public EmailInboundProcessor(IEmailMessageLedger ledger, IEmailMailboxStateStore stateStore, MimeMessageParser parser,
            SenderAuthenticator authenticator, IConversationService conversationService, IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore, ITemplatingEngine templatingEngine, Instance<IAttachmentStore> attachmentStore,
            EmailOwnerNotifier ownerNotifier, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.inbound.stranded-after", defaultValue = "30m") Duration strandedAfter,
            @ConfigProperty(name = "eddi.email.ledger-retention", defaultValue = "30d") Duration retention) {
        this.ledger = ledger;
        this.stateStore = stateStore;
        this.parser = parser;
        this.authenticator = authenticator;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.userConversationStore = userConversationStore;
        this.templatingEngine = templatingEngine;
        this.attachmentStore = attachmentStore;
        this.ownerNotifier = ownerNotifier;
        this.metrics = new EmailMetrics(meterRegistry);
        this.strandedAfter = strandedAfter == null || strandedAfter.isNegative() || strandedAfter.isZero() ? Duration.ofMinutes(30) : strandedAfter;
        this.retention = retention == null || retention.isNegative() || retention.isZero() ? Duration.ofDays(30) : retention;
    }

    Duration strandedAfter() {
        return strandedAfter;
    }

    /** Processes one message. Never throws for anything a mail can cause. */
    public Disposition process(IntegrationContext ctx, MailboxMessage message, Instant now) {
        String id = ctx.integrationId();
        EmailChannelSettings settings = ctx.settings();

        // 1. Seen before? Decided by the envelope's Message-ID, before the body is
        // fetched: a deferred or settled mail costs no download.
        String envelopeId = MessageIds.parseFirst(message.envelopeMessageId()).orElse(null);
        if (envelopeId != null) {
            Optional<LedgerEntry> existing = ledger.findByMessage(id, Direction.IN, envelopeId);
            if (existing.isPresent()) {
                Disposition known = requeueOrDisposition(existing.get(), now);
                if (known != null) {
                    if (known == Disposition.SETTLED) {
                        count(ctx, EmailMetrics.DUPLICATE);
                    }
                    return known;
                }
            }
        }

        // 2. Size, from RFC822.SIZE, before fetching.
        if (message.size() > settings.maxMessageBytes()) {
            return refuseUnread(ctx, envelopeId != null ? envelopeId : uidKey(message), message, "too_large", EmailMetrics.TOO_LARGE, now);
        }
        byte[] raw;
        try {
            raw = message.fetchRaw(settings.maxMessageBytes());
        } catch (MailboxException e) {
            if (e.getCategory() == MailboxException.Category.TOO_LARGE) {
                return refuseUnread(ctx, envelopeId != null ? envelopeId : uidKey(message), message, "too_large", EmailMetrics.TOO_LARGE, now);
            }
            LOGGER.warnf("Email integration %s: fetching a message failed (%s) — left for the next poll", sanitize(id), e.getCategory());
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.DEFERRED;
        }

        // 3. Parse. A refusal is recorded under the parser's own synthetic key.
        InboundMail parsed;
        try {
            parsed = parser.parse(raw, MailLimits.defaults().withMaxRawBytes(settings.maxMessageBytes()), settings.stripQuotedHistory());
        } catch (MailParseException e) {
            boolean tooLarge = e.getReason() == MailParseException.Reason.TOO_LARGE || e.getReason() == MailParseException.Reason.HEADERS_TOO_LARGE;
            // Keyed by the envelope's Message-ID when there is one, so a re-poll settles
            // the mail from the pre-check instead of downloading it again.
            return refuseUnread(ctx, envelopeId != null ? envelopeId : "sha256:" + sha256Hex(raw), message,
                    tooLarge ? "too_large" : "parse_error:" + e.getReason().name(),
                    tooLarge ? EmailMetrics.TOO_LARGE : EmailMetrics.PARSE_ERROR, now);
        }
        String sender = parsed.fromAddress() == null ? null : AddressNormalizer.tryNormalize(parsed.fromAddress()).orElse(null);

        // 4. The ledger row, before anything else happens to the mail.
        LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), id, Kind.TRIGGER, Direction.IN, parsed.messageId(), null, message.sourceRef(),
                inboundKey(id, parsed.messageId()), sender, State.RECEIVED, 0, null, null, null, null, now, now, now.plus(retention));
        if (!ledger.insertIfAbsent(row)) {
            Optional<LedgerEntry> existing = ledger.findByMessage(id, Direction.IN, parsed.messageId());
            if (existing.isEmpty()) {
                count(ctx, EmailMetrics.DEFERRED);
                return Disposition.DEFERRED;
            }
            Disposition known = requeueOrDisposition(existing.get(), now);
            if (known != null) {
                if (known == Disposition.SETTLED) {
                    count(ctx, EmailMetrics.DUPLICATE);
                }
                return known;
            }
            row = ledger.findById(existing.get().id()).orElse(existing.get());
            if (row.state() != State.RECEIVED) {
                count(ctx, EmailMetrics.DEFERRED);
                return Disposition.DEFERRED;
            }
        }

        // 5. Who sent it — or rather, whether the From can be believed.
        MailAuthentication authentication = authenticator.authenticate(raw, parsed, message.internalDate(), null);
        if (authentication.temporary() && !authentication.isAuthenticated() && row.createdAt().plus(MAX_TEMPORARY_DEFERRAL).isAfter(now)) {
            return defer(ctx, row, now.plus(RETRY_SHORT), "auth_temporary", EmailMetrics.DEFERRED, now);
        }
        parsed = parsed.withAuthentication(authentication);
        boolean authenticated = authentication.isAuthenticated();

        // 6. Loops, lists, authentication requirement.
        Optional<String> loop = EmailLoopGuard.check(parsed, id, settings.address(), ctx.otherIntegrationAddresses(), ledger);
        if (loop.isPresent()) {
            return refuse(ctx, row, loop.get(), EmailMetrics.LOOP, now);
        }
        if (sender == null) {
            return refuse(ctx, row, "blocked:no_single_sender", EmailMetrics.BLOCKED, now);
        }
        if (sender.equals(settings.address()) || SenderPatterns.matchesAny(sender, settings.senderBlocklist())) {
            return refuse(ctx, row, "blocked:blocklist", EmailMetrics.BLOCKED, now);
        }
        if (!settings.senderAllowlist().isEmpty() && !SenderPatterns.matchesAny(sender, settings.senderAllowlist())) {
            return refuse(ctx, row, "blocked:not_allowlisted", EmailMetrics.BLOCKED, now);
        }
        if (!authenticated && settings.requireAuthentication()) {
            return refuse(ctx, row, "unauthenticated:" + verdictId(authentication), EmailMetrics.UNAUTHENTICATED, now);
        }

        // 7. Budgets. Over one, the mail waits in the mailbox; no sender hears
        // anything.
        if (dailyBudgetExhausted(id, settings, now)) {
            ownerNotifier.notifyOnce(id, ctx.integration().getName(), settings, Reason.BUDGET, now);
            defer(ctx, row, now.plus(RETRY_BUDGET), "budget", EmailMetrics.BUDGET, now);
            return Disposition.STOP;
        }
        if (settings.maxTurnsPerSenderPerDay() > 0
                && ledger.countInboundTurns(id, sender, startOfUtcDay(now)) >= settings.maxTurnsPerSenderPerDay()) {
            ownerNotifier.notifyOnce(id, ctx.integration().getName(), settings, Reason.THROTTLED, now);
            return defer(ctx, row, startOfUtcDay(now).plus(Duration.ofDays(1)), "throttled", EmailMetrics.THROTTLED, now);
        }

        // 8. Identity and route.
        String userId = !authenticated
                ? EmailUserIdentity.anonymous()
                : settings.identityMode() == EmailChannelSettings.IdentityMode.INTEGRATION
                        ? EmailUserIdentity.integration(id)
                        : EmailUserIdentity.perSender(id, sender);
        ChannelTarget target = EmailTargetRouting.route(ctx.integration(), settings.address(), parsed);
        if (target == null || target.getTargetId() == null || target.getTargetId().isBlank()) {
            return refuse(ctx, row, "unroutable", EmailMetrics.UNROUTABLE, now);
        }

        // 9. Correlation (opt-in, authenticated senders only; sender is part of the
        // key).
        String intent = settings.correlateThreads() && authenticated && target.getType() != ChannelTarget.TargetType.GROUP
                ? correlationIntent(id, target.getTargetId(), threadRoot(parsed), sender)
                : null;
        String existingConversation = null;
        if (intent != null) {
            try {
                UserConversation mapped = userConversationStore.readUserConversation(intent, userId);
                if (mapped != null && mapped.getConversationId() != null) {
                    ConversationState state = stateOf(mapped.getConversationId());
                    if (state == ConversationState.IN_PROGRESS || state == ConversationState.AWAITING_HUMAN) {
                        return defer(ctx, row, now.plus(RETRY_SHORT), "conversation_busy", EmailMetrics.DEFERRED, now);
                    }
                    if (state != null && state != ConversationState.ENDED) {
                        existingConversation = mapped.getConversationId();
                    } else {
                        userConversationStore.deleteUserConversationIfMatches(intent, userId, mapped.getConversationId());
                    }
                }
            } catch (IResourceStore.ResourceStoreException e) {
                return defer(ctx, row, now.plus(RETRY_SHORT), "store_unavailable", EmailMetrics.DEFERRED, now);
            }
        }
        // A retry of a mail whose conversation an earlier attempt started (the turn
        // then failed and the row was given back): the turn goes into that
        // conversation, so its CONVERSATION_START never runs twice for one mail.
        if (existingConversation == null && row.conversationId() != null && target.getType() != ChannelTarget.TargetType.GROUP) {
            ConversationState state = stateOf(row.conversationId());
            if (state == ConversationState.IN_PROGRESS || state == ConversationState.AWAITING_HUMAN) {
                return defer(ctx, row, now.plus(RETRY_SHORT), "conversation_busy", EmailMetrics.DEFERRED, now);
            }
            if (state != null && state != ConversationState.ENDED) {
                existingConversation = row.conversationId();
            }
        }

        // 10. The claim. Nothing has left EDDI yet: a claim whose node dies is given
        // back and the (unmarked) mail processed again.
        Instant deadline = now.plus(strandedAfter);
        if (!ledger.transition(row.id(), State.RECEIVED, State.CLAIMED, null, deadline, null, true, now)) {
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.DEFERRED;
        }
        int attempt = row.attempts() + 1;
        String input = renderInput(settings.inputTemplate(), mailData(parsed, List.of(), true));
        // 11. The point of no return, immediately before the first side effect (a
        // conversation's CONVERSATION_START turn, a group discussion, the turn). From
        // here on the mail runs at most once.
        if (!ledger.transition(row.id(), State.CLAIMED, State.TURN_SUBMITTED, null, deadline, null, false, now)) {
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.DEFERRED;
        }
        try {
            if (target.getType() == ChannelTarget.TargetType.GROUP) {
                // A group takes a question, not a context map: the rendered input is all
                // its members see of the mail.
                var discussion = groupConversationService.startAndDiscussAsync(target.getTargetId(), input, userId, null);
                ledger.transition(row.id(), State.TURN_SUBMITTED, State.DONE, discussion.getId(), null, "group_discussion", false, now);
                count(ctx, EmailMetrics.PROCESSED);
                LOGGER.infof("Email integration %s: mail %s started group discussion %s", sanitize(id), row.id(), sanitize(discussion.getId()));
                return Disposition.SETTLED;
            }
            String conversationId = existingConversation;
            double costBefore = 0.0;
            if (conversationId == null) {
                conversationId = startConversation(target.getTargetId(), userId, id, intent);
            } else {
                costBefore = conversationCost(conversationId);
            }
            ledger.transition(row.id(), State.TURN_SUBMITTED, State.TURN_SUBMITTED, conversationId, deadline, null, false, now);
            List<Map<String, Object>> attachmentData = new ArrayList<>();
            Map<String, Context> context = new LinkedHashMap<>();
            storeAttachments(ctx, parsed, conversationId, userId, attachmentData, context);
            Map<String, Object> mail = mailData(parsed, attachmentData, false);
            context.put(MAIL_CONTEXT_KEY, new Context(Context.ContextType.object, mail));
            context.put(INTEGRATION_CONTEXT_KEY, new Context(Context.ContextType.string, id));
            input = renderInput(settings.inputTemplate(), mailData(parsed, attachmentData, true));
            submit(ctx, row, conversationId, costBefore, new InputData(input, context));
            count(ctx, EmailMetrics.PROCESSED);
            LOGGER.infof("Email integration %s: mail %s submitted to conversation %s", sanitize(id), row.id(), sanitize(conversationId));
            return Disposition.SETTLED;
        } catch (QuotaExceededException e) {
            ownerNotifier.notifyOnce(id, ctx.integration().getName(), settings, Reason.QUOTA, now);
            release(row, now.plus(RETRY_SHORT), "quota", now);
            count(ctx, EmailMetrics.BUDGET);
            return Disposition.STOP;
        } catch (RejectedExecutionException e) {
            release(row, now.plus(RETRY_SHORT), "busy", now);
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.STOP;
        } catch (AgentNotReadyException e) {
            LOGGER.warnf("Email integration %s: target %s is not deployed — mail left for a later poll", sanitize(id), sanitize(target.getName()));
            release(row, now.plus(RETRY_SHORT), "agent_not_ready", now);
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.DEFERRED;
        } catch (ConversationAwaitingApprovalException e) {
            release(row, now.plus(RETRY_SHORT), "conversation_busy", now);
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.DEFERRED;
        } catch (ConversationEndedException | IConversationService.ConversationNotFoundException e) {
            if (intent != null && existingConversation != null) {
                try {
                    userConversationStore.deleteUserConversationIfMatches(intent, userId, existingConversation);
                } catch (IResourceStore.ResourceStoreException ignored) {
                    // the next attempt finds it again and retries the delete
                }
            }
            release(row, now, "conversation_ended", now);
            count(ctx, EmailMetrics.DEFERRED);
            return Disposition.DEFERRED;
        } catch (ProcessingRestrictedException e) {
            finish(row, State.REFUSED, "processing_restricted", now);
            count(ctx, EmailMetrics.BLOCKED);
            return Disposition.SETTLED;
        } catch (IConversationService.InputTooLargeException e) {
            finish(row, State.REFUSED, "too_large:input", now);
            count(ctx, EmailMetrics.TOO_LARGE);
            return Disposition.SETTLED;
        } catch (Exception e) {
            LOGGER.warnf(e, "Email integration %s: mail %s could not start its turn (attempt %d)", sanitize(id), row.id(), attempt);
            if (attempt >= MAX_ATTEMPTS) {
                finish(row, State.REFUSED, "failed:" + e.getClass().getSimpleName(), now);
                count(ctx, EmailMetrics.FAILED);
                return Disposition.SETTLED;
            }
            release(row, now.plus(RETRY_SHORT.multipliedBy(attempt)), "failed:" + e.getClass().getSimpleName(), now);
            count(ctx, EmailMetrics.FAILED);
            return Disposition.DEFERRED;
        }
    }

    /**
     * What an existing row means for a re-polled message: settled (mark it),
     * deferred (not due yet), or null when it is due and must be processed now.
     */
    static Disposition dispositionOf(LedgerEntry row, Instant now) {
        if (row.state() == State.RECEIVED || row.state() == State.CLAIMED) {
            // A live claim belongs to the poll that holds it: leave the mail alone (and
            // unmarked). A claim past its deadline is due again — the caller requeues it.
            return row.nextAttemptAt() != null && row.nextAttemptAt().isAfter(now) ? Disposition.DEFERRED : null;
        }
        // TURN_SUBMITTED, DONE, REFUSED: the turn ran, runs, or was decided against —
        // never again. (TURN_SUBMITTED after a crash is the stranded sweep's.)
        return Disposition.SETTLED;
    }

    /**
     * {@link #dispositionOf}, after giving a stale claim back: a {@code CLAIMED}
     * row past its deadline did nothing outside EDDI, so it returns to
     * {@code RECEIVED} and this mail is processed again now.
     */
    private Disposition requeueOrDisposition(LedgerEntry row, Instant now) {
        Disposition known = dispositionOf(row, now);
        if (known == null && row.state() == State.CLAIMED
                && ledger.transition(row.id(), State.CLAIMED, State.RECEIVED, null, null, "requeued_stale_claim", false, now)) {
            metrics.stranded("requeued");
            LOGGER.infof("Email integration %s: mail %s was claimed by a poll that never finished; processing it again",
                    sanitize(row.integrationId()),
                    row.id());
        }
        return known;
    }

    private String startConversation(String agentId, String userId, String integrationId, String intent) throws Exception {
        Map<String, Context> startContext = Map.of(INTEGRATION_CONTEXT_KEY, new Context(Context.ContextType.string, integrationId));
        String conversationId = conversationService.startConversation(Deployment.Environment.production, agentId, userId, startContext)
                .conversationId();
        if (intent == null) {
            return conversationId;
        }
        try {
            userConversationStore.createUserConversation(
                    new UserConversation(intent, userId, Deployment.Environment.production, agentId, conversationId));
        } catch (IResourceStore.ResourceAlreadyExistsException race) {
            UserConversation winner = userConversationStore.readUserConversation(intent, userId);
            if (winner != null && winner.getConversationId() != null && !winner.getConversationId().equals(conversationId)) {
                endQuietly(conversationId);
                return winner.getConversationId();
            }
        }
        return conversationId;
    }

    private void submit(IntegrationContext ctx, LedgerEntry row, String conversationId, double costBefore, InputData inputData) throws Exception {
        String integrationId = ctx.integrationId();
        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        completed(integrationId, row, snapshot, costBefore, false);
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        completed(integrationId, row, snapshot, costBefore, true);
                    }
                });
    }

    /**
     * The turn finished: record its cost against the day's budget and close the
     * row.
     */
    void completed(String integrationId, LedgerEntry row, SimpleConversationMemorySnapshot snapshot, double costBefore, boolean skipped) {
        Instant now = Instant.now();
        try {
            double after = snapshot != null && snapshot.getConversationCost() != null ? snapshot.getConversationCost() : 0.0;
            double delta = after - costBefore;
            if (Double.isFinite(delta) && delta > 0.0) {
                stateStore.addSpend(integrationId, now.atZone(ZoneOffset.UTC).toLocalDate().toString(), delta, now);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: the cost of mail %s could not be recorded: %s", sanitize(integrationId), row.id(), e.getMessage());
        }
        ConversationState state = snapshot != null ? snapshot.getConversationState() : null;
        String note = skipped
                ? "turn_skipped"
                : state == ConversationState.ERROR || state == ConversationState.EXECUTION_INTERRUPTED
                        ? "turn_failed"
                        : state == ConversationState.AWAITING_HUMAN ? "awaiting_human" : null;
        if (skipped) {
            LOGGER.warnf("Email integration %s: the turn of mail %s was skipped by the conversation (state %s); the mail is not retried",
                    sanitize(integrationId), row.id(), state);
        }
        try {
            ledger.transition(row.id(), State.TURN_SUBMITTED, State.DONE, null, null, note, false, now);
        } catch (RuntimeException e) {
            // The stranded sweep resolves the row from the conversation's state.
            LOGGER.warnf("Email integration %s: mail %s could not be marked done: %s", sanitize(integrationId), row.id(), e.getMessage());
        }
    }

    private void storeAttachments(IntegrationContext ctx, InboundMail mail, String conversationId, String userId,
                                  List<Map<String, Object>> described, Map<String, Context> context) {
        EmailChannelSettings settings = ctx.settings();
        if (settings.attachments() == EmailChannelSettings.Attachments.DROP) {
            return;
        }
        IAttachmentStore store = attachmentStore != null && attachmentStore.isResolvable() ? attachmentStore.get() : null;
        int stored = 0;
        for (MailAttachment attachment : mail.attachments()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("filename", attachment.filename());
            entry.put("contentType", attachment.contentType());
            entry.put("size", attachment.size());
            entry.put("inline", attachment.inline());
            String skipped = null;
            if (settings.attachments() == EmailChannelSettings.Attachments.STORE) {
                skipped = storeRefusal(attachment, settings.attachmentMimeTypes(), store, stored);
                if (skipped == null) {
                    try {
                        var saved = store.store(attachment.content().toByteArray(), mimeOf(attachment), attachment.filename(), conversationId, null,
                                userId);
                        entry.put("attachmentId", saved.storageRef());
                        Map<String, Object> reference = new LinkedHashMap<>();
                        reference.put("storageRef", saved.storageRef());
                        if (attachment.filename() != null) {
                            reference.put("fileName", attachment.filename());
                        }
                        context.put("attachment_" + stored, new Context(Context.ContextType.object, reference));
                        stored++;
                    } catch (IAttachmentStore.AttachmentStoreException | RuntimeException e) {
                        skipped = "store_refused";
                        LOGGER.infof("Email integration %s: an attachment was not stored: %s", sanitize(ctx.integrationId()), e.getMessage());
                    }
                }
            }
            if (skipped != null) {
                entry.put("skipped", skipped);
            }
            described.add(entry);
        }
    }

    /**
     * Why an attachment is not stored, or null when it may be. Executables never.
     */
    static String storeRefusal(MailAttachment attachment, List<String> allowedTypes, IAttachmentStore store, int storedSoFar) {
        if (attachment.executable() || attachment.skipReason() == MailAttachment.SkipReason.EXECUTABLE) {
            return "executable";
        }
        if (!attachment.hasContent()) {
            return attachment.skipReason() == MailAttachment.SkipReason.TOO_LARGE ? "too_large" : "no_content";
        }
        if (!mimeAllowed(mimeOf(attachment), allowedTypes)) {
            return "type_not_allowed";
        }
        if (store == null) {
            return "store_unavailable";
        }
        if (storedSoFar >= MAX_STORED_ATTACHMENTS) {
            return "too_many";
        }
        return null;
    }

    static boolean mimeAllowed(String mimeType, List<String> allowedTypes) {
        if (mimeType == null || allowedTypes == null) {
            return false;
        }
        String type = mimeType.toLowerCase(Locale.ROOT);
        for (String allowed : allowedTypes) {
            String pattern = allowed.trim().toLowerCase(Locale.ROOT);
            if (pattern.endsWith("/*") ? type.startsWith(pattern.substring(0, pattern.length() - 1)) : type.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String mimeOf(MailAttachment attachment) {
        String type = attachment.contentType();
        if (type == null) {
            return "application/octet-stream";
        }
        int semicolon = type.indexOf(';');
        return (semicolon >= 0 ? type.substring(0, semicolon) : type).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The {@code mail} map: what the agent sees of the message (email plan §10).
     * Untrusted input, every field of it. {@code text} only goes to the template,
     * not into the context (the rendered input already carries it).
     */
    static Map<String, Object> mailData(InboundMail mail, List<Map<String, Object>> attachments, boolean withText) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", mail.fromAddress());
        data.put("fromName", mail.fromName());
        data.put("to", mail.to().stream().map(MailAddress::address).toList());
        data.put("cc", mail.cc().stream().map(MailAddress::address).toList());
        data.put("subject", mail.subject());
        data.put("date", mail.date() == null ? null : mail.date().toString());
        data.put("messageId", mail.messageId());
        data.put("authentication", verdictId(mail.authentication()));
        data.put("signingDomain", mail.authentication().signingDomain());
        data.put("quotedHistoryRemoved", mail.quotedHistoryRemoved());
        data.put("attachments", attachments);
        data.put("anomalies", mail.anomalies().stream().map(a -> a.name().toLowerCase(Locale.ROOT)).sorted().toList());
        if (withText) {
            data.put("text", mail.text());
        }
        return data;
    }

    /**
     * The author's template rendered with the mail as data. The template is
     * configuration; the mail never is — Qute substitutes values, it does not
     * re-evaluate them, so a {@code {…}} in a subject is printed, not run. A
     * template that cannot be rendered falls back to subject and text joined in
     * Java, with no template involved.
     */
    String renderInput(String template, Map<String, Object> mail) {
        String fallback = String.valueOf(mail.getOrDefault("subject", "")) + "\n\n" + mail.getOrDefault("text", "");
        try {
            String rendered = templatingEngine.processTemplate(template, Map.of(MAIL_CONTEXT_KEY, mail), ITemplatingEngine.TemplateMode.TEXT);
            return rendered == null || rendered.isBlank() ? fallback : rendered;
        } catch (Exception e) {
            LOGGER.warnf("Email inputTemplate could not be rendered — using subject and text: %s", sanitize(e.getMessage()));
            return fallback;
        }
    }

    static String verdictId(MailAuthentication authentication) {
        return authentication.verdict().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * First id in References, else In-Reply-To, else the message itself. No
     * subjects.
     */
    static String threadRoot(InboundMail mail) {
        if (!mail.references().isEmpty()) {
            return mail.references().get(0);
        }
        return mail.inReplyTo() != null ? mail.inReplyTo() : mail.messageId();
    }

    /**
     * The mapping key of a correlated thread:
     * {@code email:<integrationId>:<agentId>:<sha256(threadRoot, sender)>}.
     * <p>
     * <b>The hijack guard.</b> The sender's address is part of the key, so a
     * continuation lands in a conversation only when its authenticated sender is
     * the one who started it — whatever the identity mode. Under
     * {@code identityMode: integration} every sender shares one user id, so a key
     * of the thread alone would let anyone holding a thread's Message-ID (it
     * travels in every forward) join that conversation; with the sender in the key
     * they start their own.
     */
    static String correlationIntent(String integrationId, String agentId, String threadRoot, String sender) {
        return EmailUserIdentity.PREFIX + integrationId + ":" + agentId + ":"
                + sha256Hex((threadRoot + "\n" + sender).getBytes(StandardCharsets.UTF_8));
    }

    private boolean dailyBudgetExhausted(String integrationId, EmailChannelSettings settings, Instant now) {
        if (settings.dailyCostBudgetUsd() == null) {
            return false;
        }
        return budgetExhausted(stateStore, integrationId, settings, now);
    }

    /** Whether the integration's spend today reached its daily budget. */
    static boolean budgetExhausted(IEmailMailboxStateStore stateStore, String integrationId, EmailChannelSettings settings, Instant now) {
        if (settings.dailyCostBudgetUsd() == null) {
            return false;
        }
        try {
            String today = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
            return stateStore.read(integrationId).filter(state -> today.equals(state.budgetDay()))
                    .map(state -> state.spentUsd() >= settings.dailyCostBudgetUsd().doubleValue()).orElse(false);
        } catch (RuntimeException e) {
            // An unreadable budget is treated as spent: the mail waits, it is not run
            // uncapped.
            return true;
        }
    }

    static Instant startOfUtcDay(Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private ConversationState stateOf(String conversationId) {
        try {
            return conversationService.getConversationState(conversationId);
        } catch (IConversationService.ConversationNotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            return ConversationState.IN_PROGRESS;
        }
    }

    private double conversationCost(String conversationId) {
        try {
            var snapshot = conversationService.readConversation(conversationId, false, true, List.of());
            return snapshot != null && snapshot.getConversationCost() != null ? snapshot.getConversationCost() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void endQuietly(String conversationId) {
        try {
            conversationService.endConversation(conversationId, "system:lost-create-race");
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not end orphaned email conversation %s", sanitize(conversationId));
        }
    }

    private Disposition refuseUnread(IntegrationContext ctx, String key, MailboxMessage message, String reason, String outcome, Instant now) {
        LedgerEntry row = new LedgerEntry(UUID.randomUUID().toString(), ctx.integrationId(), Kind.TRIGGER, Direction.IN, key, null,
                message.sourceRef(), inboundKey(ctx.integrationId(), key), null, State.REFUSED, 0, null, null, null, reason, now, now,
                now.plus(retention));
        if (ledger.insertIfAbsent(row)) {
            count(ctx, outcome);
            audit(ctx, row, reason, null);
            LOGGER.infof("Email integration %s: mail refused (%s)", sanitize(ctx.integrationId()), reason);
        } else {
            count(ctx, EmailMetrics.DUPLICATE);
        }
        return Disposition.SETTLED;
    }

    private Disposition refuse(IntegrationContext ctx, LedgerEntry row, String reason, String outcome, Instant now) {
        finish(row, State.REFUSED, reason, now);
        count(ctx, outcome);
        audit(ctx, row, reason, row.recipient());
        LOGGER.infof("Email integration %s: mail %s refused (%s)", sanitize(ctx.integrationId()), row.id(), reason);
        return Disposition.SETTLED;
    }

    private Disposition defer(IntegrationContext ctx, LedgerEntry row, Instant until, String reason, String outcome, Instant now) {
        ledger.transition(row.id(), State.RECEIVED, State.RECEIVED, null, until, reason, false, now);
        count(ctx, outcome);
        return Disposition.DEFERRED;
    }

    /**
     * Back from TURN_SUBMITTED to RECEIVED: the turn did not start, so it may be
     * tried again.
     */
    private void release(LedgerEntry row, Instant until, String reason, Instant now) {
        ledger.transition(row.id(), State.TURN_SUBMITTED, State.RECEIVED, null, until, reason, false, now);
    }

    private void finish(LedgerEntry row, State terminal, String reason, Instant now) {
        if (!ledger.transition(row.id(), State.RECEIVED, terminal, null, null, reason, false, now)) {
            ledger.transition(row.id(), State.TURN_SUBMITTED, terminal, null, null, reason, false, now);
        }
    }

    /**
     * One audit entry per refused mail: integration, reason category, ledger row,
     * Message-ID and the sender's domain plus a SHA-256 of the address — never the
     * address itself (the audit ledger is write-once) and never content.
     */
    private void audit(IntegrationContext ctx, LedgerEntry row, String reason, String sender) {
        if (auditLedger == null || !auditLedger.isResolvable()) {
            return;
        }
        try {
            AuditLedgerService service = auditLedger.get();
            if (!service.isEnabled()) {
                return;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("integrationId", ctx.integrationId());
            detail.put("outcome", "refused");
            int colon = reason.indexOf(':');
            detail.put("reason", colon > 0 ? reason.substring(0, colon) : reason);
            detail.put("detail", reason);
            detail.put("ledgerId", row.id());
            detail.put("messageId", row.messageId());
            if (sender != null && sender.indexOf('@') > 0) {
                detail.put("senderDomain", sender.substring(sender.lastIndexOf('@') + 1));
                detail.put("senderHash", "sha256:" + sha256Hex(sender.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
            }
            service.submit(new AuditEntry(UUID.randomUUID().toString(), null, null, null, null, null, -1, "email.inbound", "email", -1, 0L, Map.of(),
                    detail, null, null, List.of(), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not audit a refused mail: %s", e.getMessage());
        }
    }

    private void count(IntegrationContext ctx, String outcome) {
        metrics.inbound(ctx.integrationId(), ctx.settings().transport(), outcome);
    }

    static String inboundKey(String integrationId, String messageId) {
        return "in:" + sha256Hex((integrationId + "\n" + messageId).getBytes(StandardCharsets.UTF_8));
    }

    private static String uidKey(MailboxMessage message) {
        return "<uid." + sha256Hex(message.sourceRef().getBytes(StandardCharsets.UTF_8)).substring(0, 32) + "@eddi.invalid>";
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
