/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.reply.EmailReplySender;
import ai.labs.eddi.integrations.email.reply.EmailReplyThread;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Maintenance for the inbound side of the email channel, as a Quarkus
 * {@code @Scheduled(identity=…)} job (email plan §12).
 * <p>
 * <b>Stale claims.</b> A {@code CLAIMED} row past its deadline did nothing
 * outside EDDI and goes back to {@code RECEIVED}; its mail is still unmarked in
 * the polled folder, so the next poll fetches and processes it
 * ({@code outcome=requeued}).
 * <p>
 * <b>Stranded turns.</b> An inbound row moves to {@code TURN_SUBMITTED} with a
 * deadline ({@code eddi.email.inbound.stranded-after}, default 30 min) and to
 * {@code DONE} from the turn's completion callback. A row still submitted past
 * its deadline lost that callback — the node died, or the callback's write
 * failed. The sweep resolves the row from the conversation it recorded:
 * <ul>
 * <li>the conversation holds the mail's step and is idle (READY, ENDED), failed
 * (ERROR, interrupted) or waiting for a human (AWAITING_HUMAN): the turn ran —
 * {@code DONE}, the state noted ({@code outcome=done|failed|awaiting_human});
 * an ended or failed conversation without the step is resolved the same way,
 * since its turn may have partly run;</li>
 * <li>still IN_PROGRESS: the deadline moves on, the turn is not touched;</li>
 * <li>no conversation recorded, or it is gone: the node died while starting the
 * conversation, whose {@code CONVERSATION_START} turn may already have called
 * tools. The mail is <b>not</b> run again — that could repeat side effects —
 * and the row ends {@code REFUSED(stranded)} ({@code outcome=lost}), logged at
 * WARN with the ledger id, and the integration's owners get the once-a-day
 * {@code lost} notice.</li>
 * <li>the conversation is idle or paused but holds no step for the mail: the
 * conversation was started, and the node died before the mail's own turn ran.
 * Nothing of the mail has run, so it is <b>submitted again</b>: the row goes
 * back to {@code RECEIVED}, keeping its conversation, and the next poll finds
 * the mail in the mailbox by its Message-ID and runs its turn in that
 * conversation ({@code outcome=resubmitted}). Once: the transition is
 * conditional, the turn carries the mail's ledger key as its idempotency key,
 * and a later sweep sees the step.</li>
 * </ul>
 * <b>Conversation mode</b> also rebuilds what the dead node did not send: a
 * mail whose step exists but has no reply row gets its reply rendered from the
 * stored conversation, and a mail whose turn paused gets its pause notice
 * ({@link EmailReplySender#ensureFor}, idempotent —
 * {@code outcome=reply_rebuilt}). A rebuild that fails leaves the row
 * {@code TURN_SUBMITTED} with its deadline moved on, so a later sweep tries
 * again ({@link #MAX_REBUILD_ATTEMPTS} times in all) before the row is closed
 * without the reply.
 * <p>
 * Each resolution is a conditional transition, so several nodes running the
 * sweep at once resolve each row once.
 * <p>
 * <b>Schedules.</b> Every email integration gets its poll schedule back if it
 * is missing (an integration imported from a ZIP, or a schedule deleted by
 * hand).
 */
@ApplicationScoped
public class EmailInboundSweeper {

    private static final Logger LOGGER = Logger.getLogger(EmailInboundSweeper.class);

    static final int BATCH = 50;

    /**
     * The note on a row handed back because the mail's turn never ran; the poll
     * finds such a row by its recorded conversation.
     */
    public static final String RESUBMIT = "resubmit";

    /**
     * The note on a submitted row whose reply could not be rebuilt yet, followed by
     * how many sweeps failed so far.
     */
    static final String REBUILD_FAILED = "reply_rebuild_failed:";

    /**
     * Sweeps that try to rebuild a reply before the row is closed without it — one
     * every {@code stranded-after}, so a store outage of a few hours is covered and
     * a reply that can never be built does not keep its row open for ever.
     */
    static final int MAX_REBUILD_ATTEMPTS = 5;

    private final IEmailMessageLedger ledger;
    private final IConversationService conversationService;
    private final EmailIntegrationDirectory directory;
    private final EmailPollScheduler scheduler;
    private final EmailOwnerNotifier ownerNotifier;
    private final EmailMetrics metrics;
    private final Duration strandedAfter;

    /**
     * Conversation mode's replies, for the rebuild. Field-injected so the
     * constructor stays the one the tests use; null there, and then no reply is
     * rebuilt.
     */
    @Inject
    EmailReplySender replies;

    @Inject
    public EmailInboundSweeper(IEmailMessageLedger ledger, IConversationService conversationService, EmailIntegrationDirectory directory,
            EmailPollScheduler scheduler, EmailOwnerNotifier ownerNotifier, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.inbound.stranded-after", defaultValue = "30m") Duration strandedAfter) {
        this.ledger = ledger;
        this.conversationService = conversationService;
        this.directory = directory;
        this.scheduler = scheduler;
        this.ownerNotifier = ownerNotifier;
        this.metrics = new EmailMetrics(meterRegistry);
        this.strandedAfter = strandedAfter == null || strandedAfter.isNegative() || strandedAfter.isZero() ? Duration.ofMinutes(30) : strandedAfter;
    }

    @Scheduled(every = "${eddi.email.inbound.sweep-interval:5m}", delayed = "60s", identity = "email-inbound-sweeper",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        Instant now = Instant.now();
        try {
            resolveStranded(now);
        } catch (RuntimeException e) {
            LOGGER.warnf("Email inbound sweep (stranded turns) failed: %s", e.getMessage());
        }
        try {
            ensureSchedules();
        } catch (RuntimeException e) {
            LOGGER.warnf("Email inbound sweep (poll schedules) failed: %s", e.getMessage());
        }
    }

    /**
     * Gives stale claims back and resolves submitted turns past their deadline.
     * Returns how many rows were changed.
     */
    int resolveStranded(Instant now) {
        int resolved = 0;
        // A CLAIMED row did nothing outside EDDI. Its mail was not marked or moved
        // (only a settled row is), and the poll cursor never passes an unsettled mail,
        // so the mail is still in the polled folder at or above the cursor: giving the
        // row back to RECEIVED is enough for the next poll to fetch and process it
        // again. The sweep opens no IMAP session of its own — the mailbox belongs to
        // whichever node holds its poll schedule's cluster claim.
        for (LedgerEntry row : ledger.findDue(Kind.TRIGGER, State.CLAIMED, now, BATCH)) {
            if (ledger.transition(row.id(), State.CLAIMED, State.RECEIVED, null, null, "requeued_stale_claim", false, now)) {
                resolved++;
                metrics.stranded("requeued");
                LOGGER.infof("Email integration %s: inbound mail %s was claimed by a poll that never finished; the next poll processes it again",
                        sanitize(row.integrationId()), row.id());
            }
        }
        for (LedgerEntry row : ledger.findDue(Kind.TRIGGER, State.TURN_SUBMITTED, now, BATCH)) {
            ConversationState state = null;
            boolean missing = row.conversationId() == null;
            if (!missing) {
                try {
                    state = conversationService.getConversationState(row.conversationId());
                    missing = state == null;
                } catch (IConversationService.ConversationNotFoundException e) {
                    missing = true;
                } catch (RuntimeException e) {
                    // The store could not answer: try again next sweep.
                    continue;
                }
            }
            if (missing) {
                if (ledger.transition(row.id(), State.TURN_SUBMITTED, State.REFUSED, null, null, "stranded", false, now)) {
                    resolved++;
                    metrics.stranded("lost");
                    LOGGER.warnf("Email integration %s: inbound mail %s passed the point of no return but no conversation was recorded (the "
                            + "node stopped while starting it); it may have partly run, so it is not run again", sanitize(row.integrationId()),
                            row.id());
                    notifyLost(row, now);
                }
                continue;
            }
            if (state == ConversationState.IN_PROGRESS) {
                ledger.transition(row.id(), State.TURN_SUBMITTED, State.TURN_SUBMITTED, null, now.plus(strandedAfter), null, false, now);
                continue;
            }
            // Unreadable: resolved as before, without a resubmission — when in doubt the
            // mail is not run a second time.
            SimpleConversationMemorySnapshot snapshot = readFull(row.conversationId());
            boolean idle = state == ConversationState.READY || state == ConversationState.AWAITING_HUMAN;
            int step = snapshot == null ? -1 : EmailReplyThread.mailStep(snapshot.getConversationOutputs(), row.messageId());
            if (snapshot != null && step < 0 && idle) {
                if (ledger.transition(row.id(), State.TURN_SUBMITTED, State.RECEIVED, null, now, RESUBMIT, false, now)) {
                    resolved++;
                    metrics.stranded("resubmitted");
                    LOGGER.warnf("Email integration %s: the turn of inbound mail %s never ran in conversation %s (the node stopped after "
                            + "starting it); the next poll submits it again", sanitize(row.integrationId()), row.id(),
                            sanitize(row.conversationId()));
                }
                continue;
            }
            if (step >= 0 && !rebuildReply(row, snapshot, now)) {
                // Closing the row now would leave the reply to nobody: the outbound row may
                // not exist yet, and DONE leaves every recovery query. Kept for a later sweep.
                int failures = rebuildFailures(row) + 1;
                if (failures < MAX_REBUILD_ATTEMPTS) {
                    ledger.transition(row.id(), State.TURN_SUBMITTED, State.TURN_SUBMITTED, null, now.plus(strandedAfter), REBUILD_FAILED + failures,
                            false, now);
                    continue;
                }
                LOGGER.warnf("Email integration %s: the reply to inbound mail %s could not be rebuilt in %d sweeps; the mail is closed "
                        + "without it", sanitize(row.integrationId()), row.id(), failures);
            }
            String outcome = switch (state) {
                case ERROR, EXECUTION_INTERRUPTED -> "failed";
                case AWAITING_HUMAN -> "awaiting_human";
                default -> "done";
            };
            if (ledger.transition(row.id(), State.TURN_SUBMITTED, State.DONE, null, null, "recovered:" + outcome, false, now)) {
                resolved++;
                metrics.stranded(outcome);
            }
        }
        return resolved;
    }

    /**
     * Conversation mode: the reply (or pause notice) the dead node did not queue.
     *
     * @return false when it could not be rebuilt (and may be tried again); true
     *         when it exists now or none is due
     */
    private boolean rebuildReply(LedgerEntry row, SimpleConversationMemorySnapshot snapshot, Instant now) {
        if (replies == null) {
            return true;
        }
        try {
            Optional<EmailIntegration> integration = directory.find(row.integrationId());
            if (integration.isPresent() && integration.get().settings().replies()
                    && replies.ensureFor(integration.get().settings(), row, snapshot, now)) {
                metrics.stranded("reply_rebuilt");
                LOGGER.infof("Email integration %s: rebuilt the reply to inbound mail %s from conversation %s", sanitize(row.integrationId()),
                        row.id(), sanitize(row.conversationId()));
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: the reply to inbound mail %s could not be rebuilt: %s", sanitize(row.integrationId()), row.id(),
                    e.getMessage());
            return false;
        }
        return true;
    }

    /** How many sweeps failed to rebuild the reply of {@code row} so far. */
    static int rebuildFailures(LedgerEntry row) {
        String note = row.lastError();
        if (note == null || !note.startsWith(REBUILD_FAILED)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(note.substring(REBUILD_FAILED.length())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private SimpleConversationMemorySnapshot readFull(String conversationId) {
        try {
            return conversationService.readConversation(conversationId, true, false, List.of());
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyLost(LedgerEntry row, Instant now) {
        try {
            var integration = directory.find(row.integrationId());
            ownerNotifier.notifyOnce(row.integrationId(), integration.map(i -> i.configuration().getName()).orElse(null),
                    integration.map(EmailIntegrationDirectory.EmailIntegration::settings).orElse(null), EmailOwnerNotifier.Reason.LOST, now);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not notify the owners of a lost mail: %s", e.getMessage());
        }
    }

    /**
     * Creates the poll schedule of every email integration that lacks one.
     */
    int ensureSchedules() {
        int created = 0;
        for (EmailIntegration integration : directory.scan()) {
            EmailChannelSettings settings = integration.settings();
            if (settings.mode() == null
                    || !EmailChannelSettings.validate(integration.configuration().getPlatformConfig()).isEmpty()) {
                continue;
            }
            try {
                if (scheduler.ensure(integration.id(), settings)) {
                    created++;
                    LOGGER.infof("Email integration %s had no poll schedule; created one", sanitize(integration.id()));
                }
            } catch (Exception e) {
                LOGGER.warnf("Email integration %s: its poll schedule could not be checked: %s", sanitize(integration.id()), e.getMessage());
            }
        }
        return created;
    }
}
