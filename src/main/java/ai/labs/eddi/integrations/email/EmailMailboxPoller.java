/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.Disposition;
import ai.labs.eddi.integrations.email.EmailInboundProcessor.IntegrationContext;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.EmailOwnerNotifier.Reason;
import ai.labs.eddi.integrations.email.ledger.IEmailMailboxStateStore;
import ai.labs.eddi.integrations.email.ledger.IEmailMailboxStateStore.MailboxState;
import ai.labs.eddi.integrations.email.transport.ImapMailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport;
import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxMessage;
import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxSession;
import ai.labs.eddi.integrations.email.transport.MailboxException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * One poll of one mailbox, fired by its {@code email-poll:<integrationId>}
 * schedule (email plan §7.1). The schedule's cluster-wide claim means one node
 * polls a mailbox at a time.
 * <p>
 * <b>The cursor.</b> {@code UIDVALIDITY} plus the last UID up to which every
 * message is settled, in {@link IEmailMailboxStateStore}. A deferred message (a
 * budget, a temporary authentication result, a quota) holds the cursor back, so
 * it is seen again; later messages are still processed, and when they come
 * round again the ledger or the {@code $EddiProcessed} keyword settles them
 * without a download. A changed {@code UIDVALIDITY} starts the folder over,
 * deduplicated by the ledger and the keyword, and is counted.
 * <p>
 * <b>Mail that predates the integration is not processed.</b> The first poll
 * starts the cursor at {@code UIDNEXT} and records the instant; mail whose
 * {@code INTERNALDATE} is older is skipped on every later scan, a rescan after
 * a {@code UIDVALIDITY} change included. Connecting an integration to a full
 * mailbox does not make the agent work through years of mail.
 * <p>
 * <b>Bounded work.</b> At most {@code eddi.email.poll.max-messages-per-poll}
 * messages are processed and {@code eddi.email.poll.max-duration} spent per
 * poll; the rest wait for the next. Turns are queued, not awaited, so the poll
 * thread is held only for parsing, authentication, the claim and the
 * {@code CONVERSATION_START} turn of a new conversation, which runs inline.
 * <p>
 * <b>Failures back off.</b> A connection, login, policy or folder failure is
 * recorded on the state (failure count, back-off, a short reason without
 * secrets), logged once per failure and counted on
 * {@code eddi.email.poll.duration{outcome=error}}; the next poll waits
 * {@code pollSeconds × 2^(failures-1)}, at most an hour. The schedule itself is
 * never dead-lettered by a mail server outage.
 */
@ApplicationScoped
public class EmailMailboxPoller {

    private static final Logger LOGGER = Logger.getLogger(EmailMailboxPoller.class);

    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /**
     * Slack on the "older than the integration" rule: {@code INTERNALDATE} has
     * whole-second precision and server clocks drift, so mail delivered just around
     * the first poll is never mistaken for old mail. (The UID cursor already
     * excludes pre-existing mail; this rule matters for rescans.)
     */
    static final Duration SINCE_GRACE = Duration.ofMinutes(5);

    /** What a poll did, for the schedule's fire log. */
    public record PollReport(boolean failed, String message) {
    }

    private final EmailIntegrationDirectory directory;
    private final EmailInboundProcessor processor;
    private final IEmailMailboxStateStore stateStore;
    private final MailTransport transport;
    private final EmailPollScheduler scheduler;
    private final EmailOwnerNotifier ownerNotifier;
    private final EmailMetrics metrics;
    private final int maxMessagesPerPoll;
    private final Duration maxDuration;

    @Inject
    public EmailMailboxPoller(EmailIntegrationDirectory directory, EmailInboundProcessor processor, IEmailMailboxStateStore stateStore,
            ImapMailTransport transport, EmailPollScheduler scheduler, EmailOwnerNotifier ownerNotifier, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.email.poll.max-messages-per-poll", defaultValue = "50") int maxMessagesPerPoll,
            @ConfigProperty(name = "eddi.email.poll.max-duration", defaultValue = "2m") Duration maxDuration) {
        this(directory, processor, stateStore, (MailTransport) transport, scheduler, ownerNotifier, meterRegistry, maxMessagesPerPoll, maxDuration);
    }

    /** For tests: any transport. */
    public EmailMailboxPoller(EmailIntegrationDirectory directory, EmailInboundProcessor processor, IEmailMailboxStateStore stateStore,
            MailTransport transport, EmailPollScheduler scheduler, EmailOwnerNotifier ownerNotifier, MeterRegistry meterRegistry,
            int maxMessagesPerPoll, Duration maxDuration) {
        this.directory = directory;
        this.processor = processor;
        this.stateStore = stateStore;
        this.transport = transport;
        this.scheduler = scheduler;
        this.ownerNotifier = ownerNotifier;
        this.metrics = new EmailMetrics(meterRegistry);
        this.maxMessagesPerPoll = Math.max(1, maxMessagesPerPoll);
        this.maxDuration = maxDuration == null || maxDuration.isNegative() || maxDuration.isZero() ? Duration.ofMinutes(2) : maxDuration;
    }

    /** Polls the mailbox of {@code integrationId} once. Never throws. */
    public PollReport poll(String integrationId) {
        Instant started = Instant.now();
        Optional<EmailIntegration> found;
        try {
            found = directory.find(integrationId);
        } catch (RuntimeException e) {
            return new PollReport(true, "The channel store could not be read: " + e.getMessage());
        }
        if (found.isEmpty()) {
            // Deleted, or no longer an email integration: the schedule removes itself.
            scheduler.remove(integrationId);
            return new PollReport(false, "The integration no longer exists; its poll schedule was removed.");
        }
        EmailIntegration integration = found.get();
        List<String> errors = EmailChannelSettings.validate(integration.configuration().getPlatformConfig());
        EmailChannelSettings settings = integration.settings();
        if (!errors.isEmpty() || settings.mode() != EmailChannelSettings.Mode.TRIGGER) {
            LOGGER.errorf("Email integration %s is not polled: its configuration does not validate: %s", sanitize(integrationId),
                    sanitize(String.join(" ", errors)));
            return new PollReport(true, "The integration's configuration does not validate.");
        }
        String outcome = "ok";
        try {
            return pollOnce(integration, settings, started);
        } catch (MailboxException e) {
            outcome = "error";
            return fail(integrationId, settings, e, started);
        } catch (RuntimeException e) {
            outcome = "error";
            LOGGER.errorf(e, "Email integration %s: the poll failed unexpectedly", sanitize(integrationId));
            return fail(integrationId, settings, new MailboxException(MailboxException.Category.CONNECTION, e.getClass().getSimpleName(), e),
                    started);
        } finally {
            metrics.pollDuration(integrationId, settings.transport(), outcome, Duration.between(started, Instant.now()));
        }
    }

    private PollReport pollOnce(EmailIntegration integration, EmailChannelSettings settings, Instant started) throws MailboxException {
        String id = integration.id();
        Optional<MailboxState> state = stateStore.read(id);
        if (state.isPresent() && state.get().backoffUntil() != null && state.get().backoffUntil().isAfter(started)) {
            return new PollReport(false, "Backing off after " + state.get().failures() + " failed poll(s) until " + state.get().backoffUntil());
        }
        if (EmailInboundProcessor.budgetExhausted(stateStore, id, settings, started)) {
            ownerNotifier.notifyOnce(id, integration.configuration().getName(), settings, Reason.BUDGET, started);
            return new PollReport(false, "The daily cost budget is used up; mail waits for the next UTC day.");
        }
        var context = new IntegrationContext(id, integration.configuration(), settings, directory.otherAddresses(id));
        try (MailboxSession session = transport.open(id, settings)) {
            long uidValidity = session.uidValidity();
            boolean sameFolder = state.isPresent() && settings.folder().equals(state.get().folder());
            long lastUid;
            Instant since;
            if (state.isEmpty() || state.get().uidValidity() == 0 || !sameFolder) {
                // First poll of this folder: start after what is already there.
                lastUid = Math.max(0, session.uidNext() - 1);
                since = state.isPresent() && state.get().since() != null && sameFolder ? state.get().since() : started;
                stateStore.saveCursor(id, settings.folder(), uidValidity, lastUid, since, started);
                return new PollReport(false, "Cursor initialised; mail delivered from now on is processed.");
            }
            since = state.get().since();
            lastUid = state.get().lastUid();
            if (state.get().uidValidity() != uidValidity) {
                LOGGER.warnf("Email integration %s: UIDVALIDITY of '%s' changed — rescanning the folder, deduplicated by the ledger",
                        sanitize(id), sanitize(settings.folder()));
                metrics.cursorReset(id, settings.transport());
                lastUid = 0;
            }
            long settledUpTo = lastUid;
            boolean contiguous = true;
            int processed = 0;
            int marked = 0;
            int deferred = 0;
            Instant deadline = started.plus(maxDuration);
            // Envelopes are cheap; they are listed beyond the processing cap so skipped
            // (already flagged, too old) messages do not stall the cursor.
            for (MailboxMessage message : session.messagesAfter(lastUid, maxMessagesPerPoll * 20)) {
                if (processed >= maxMessagesPerPoll || Instant.now().isAfter(deadline)) {
                    break;
                }
                Disposition disposition;
                if (message.alreadyProcessed()
                        || (since != null && message.internalDate() != null && message.internalDate().isBefore(since.minus(SINCE_GRACE)))) {
                    disposition = null;
                } else {
                    disposition = processor.process(context, message, Instant.now());
                    // A mail still waiting (not due) costs a ledger lookup, not a slot of
                    // the batch, so a backlog of deferred mail cannot starve new mail.
                    if (disposition != Disposition.DEFERRED) {
                        processed++;
                    }
                }
                if (disposition == Disposition.SETTLED) {
                    // Only now: the ledger row exists, so a re-poll can never re-run it.
                    try {
                        var result = session.markProcessed(message, settings);
                        metrics.marked(id, settings.transport(), result.name().toLowerCase(Locale.ROOT));
                        marked++;
                    } catch (MailboxException e) {
                        LOGGER.warnf("Email integration %s: could not mark a processed message (%s); the ledger keeps it from running "
                                + "again", sanitize(id), e.getCategory());
                    }
                }
                if (disposition == null || disposition == Disposition.SETTLED) {
                    if (contiguous) {
                        settledUpTo = message.uid();
                    }
                    continue;
                }
                deferred++;
                contiguous = false;
                if (disposition == Disposition.STOP) {
                    break;
                }
            }
            stateStore.saveCursor(id, settings.folder(), uidValidity, settledUpTo, since, Instant.now());
            return new PollReport(false, processed + " processed, " + marked + " marked, " + deferred + " deferred");
        }
    }

    private PollReport fail(String integrationId, EmailChannelSettings settings, MailboxException e, Instant now) {
        int failures = 1;
        try {
            failures = stateStore.read(integrationId).map(s -> s.failures() + 1).orElse(1);
        } catch (RuntimeException ignored) {
            // keep 1
        }
        Duration backoff = backoff(settings.pollSeconds(), failures);
        String reason = e.getCategory() + ": " + truncate(e.getMessage());
        try {
            stateStore.recordFailure(integrationId, failures, now.plus(backoff), reason, now);
        } catch (RuntimeException store) {
            LOGGER.debugf("Could not record the poll failure: %s", store.getMessage());
        }
        LOGGER.warnf("Email integration %s: poll failed (%s), attempt %d, next try in %s: %s", sanitize(integrationId), e.getCategory(), failures,
                backoff, sanitize(truncate(e.getMessage())));
        return new PollReport(true, reason);
    }

    /** {@code pollSeconds × 2^(failures-1)}, capped at {@link #MAX_BACKOFF}. */
    static Duration backoff(int pollSeconds, int failures) {
        int doublings = Math.min(Math.max(0, failures - 1), 20);
        Duration delay = Duration.ofSeconds(Math.max(1, pollSeconds)).multipliedBy(1L << doublings);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
