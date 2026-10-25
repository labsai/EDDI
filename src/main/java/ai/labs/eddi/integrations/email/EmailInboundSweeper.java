/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
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

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Maintenance for the inbound side of the email channel, as a Quarkus
 * {@code @Scheduled(identity=…)} job (email plan §12).
 * <p>
 * <b>Stranded turns.</b> An inbound row moves to {@code TURN_SUBMITTED} with a
 * deadline ({@code eddi.email.inbound.stranded-after}, default 30 min) and to
 * {@code DONE} from the turn's completion callback. A row still submitted past
 * its deadline lost that callback — the node died, or the callback's write
 * failed. In trigger mode there is no reply to rebuild, so the sweep only
 * <em>resolves</em> the row, from the conversation it recorded:
 * <ul>
 * <li>the conversation is idle (READY, ENDED), failed (ERROR, interrupted) or
 * waiting for a human (AWAITING_HUMAN): the turn ran — {@code DONE}, the state
 * noted ({@code outcome=done|failed|awaiting_human});</li>
 * <li>still IN_PROGRESS: the deadline moves on, the turn is not touched;</li>
 * <li>no conversation recorded, or it is gone: the node died between the claim
 * and the turn. The mail is <b>not</b> run again — its raw bytes are not kept,
 * and running a turn twice could repeat tool side effects — and the row ends
 * {@code REFUSED(stranded)} ({@code outcome=lost}). This is the one window in
 * which a mail can be dropped; it is logged at WARN with the ledger id.</li>
 * </ul>
 * Each resolution is a conditional transition, so several nodes running the
 * sweep at once resolve each row once.
 * <p>
 * <b>Schedules.</b> Every trigger-mode integration gets its poll schedule back
 * if it is missing (an integration imported from a ZIP, or a schedule deleted
 * by hand).
 */
@ApplicationScoped
public class EmailInboundSweeper {

    private static final Logger LOGGER = Logger.getLogger(EmailInboundSweeper.class);

    static final int BATCH = 50;

    private final IEmailMessageLedger ledger;
    private final IConversationService conversationService;
    private final EmailIntegrationDirectory directory;
    private final EmailPollScheduler scheduler;
    private final EmailMetrics metrics;
    private final Duration strandedAfter;

    @Inject
    public EmailInboundSweeper(IEmailMessageLedger ledger, IConversationService conversationService, EmailIntegrationDirectory directory,
            EmailPollScheduler scheduler, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.inbound.stranded-after", defaultValue = "30m") Duration strandedAfter) {
        this.ledger = ledger;
        this.conversationService = conversationService;
        this.directory = directory;
        this.scheduler = scheduler;
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
     * Resolves submitted turns past their deadline. Returns how many were resolved.
     */
    int resolveStranded(Instant now) {
        int resolved = 0;
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
                    LOGGER.warnf("Email integration %s: inbound mail %s was claimed but its turn never started (the node stopped in between); "
                            + "it is not run again", sanitize(row.integrationId()), row.id());
                }
                continue;
            }
            if (state == ConversationState.IN_PROGRESS) {
                ledger.transition(row.id(), State.TURN_SUBMITTED, State.TURN_SUBMITTED, null, now.plus(strandedAfter), null, false, now);
                continue;
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
     * Creates the poll schedule of every trigger-mode integration that lacks one.
     */
    int ensureSchedules() {
        int created = 0;
        for (EmailIntegration integration : directory.scan()) {
            EmailChannelSettings settings = integration.settings();
            if (settings.mode() != EmailChannelSettings.Mode.TRIGGER
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
