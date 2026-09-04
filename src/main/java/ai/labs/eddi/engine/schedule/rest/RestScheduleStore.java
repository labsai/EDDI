/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.CronDescriber;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.runtime.internal.DreamService;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * JAX-RS implementation of {@link IRestScheduleStore}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestScheduleStore implements IRestScheduleStore {

    private static final Logger LOGGER = Logger.getLogger(RestScheduleStore.class);

    /**
     * Metadata marker key/value identifying HITL approval-timeout schedules. Such
     * schedules are safety timers behind the human-oversight gate — the general
     * schedule CRUD surface must not let a non-admin fire them (which would
     * side-step the owner/admin/approver check on {@code /resume}) or disarm them
     * (defeating an ABORT/AUTO_REJECT deadline).
     */
    private static final String HITL_TYPE_KEY = HitlSchedules.METADATA_TYPE_KEY;
    private static final String HITL_TYPE_TIMEOUT = HitlSchedules.METADATA_TYPE_TIMEOUT;

    /**
     * Placeholder identity for schedules that act for the system rather than for a
     * specific end user. It is not a real principal — {@code DreamService} refuses
     * to consolidate memories for it — so ownership checks treat it as unowned.
     */
    private static final String SCHEDULER_USER_ID = DreamService.SCHEDULER_PLACEHOLDER_USER_ID;

    /** Server-side ceiling for the schedule listing page size. */
    private static final int MAX_LIST_LIMIT = 1000;

    /** Server-side ceiling for fire-log page sizes. */
    private static final int MAX_FIRE_LOG_LIMIT = 500;

    @Inject
    IScheduleStore scheduleStore;

    @Inject
    ScheduleFireExecutor fireExecutor;

    @Inject
    SchedulePollerService pollerService;

    @Inject
    SecurityIdentity identity;

    @Inject
    OwnershipValidator ownershipValidator;

    @Inject
    ResourceAccessGuard resourceAccessGuard;

    @ConfigProperty(name = "eddi.schedule.default-timezone", defaultValue = "UTC")
    String defaultTimeZone;

    @ConfigProperty(name = "eddi.schedule.min-interval-seconds", defaultValue = "60")
    long minIntervalSeconds;

    @Override
    public List<ScheduleConfiguration> readAllSchedules(String agentId, int limit, int offset) {
        try {
            // Paged and deterministically ordered. The listing used to be a single
            // hard-capped page of 500 in whatever order the store returned, so once a
            // deployment held more than that — HITL timeouts, per-user dream schedules
            // and team cadences are all created programmatically — the surplus was
            // invisible and could not be disabled or deleted through the list at all.
            int pageSize = boundedLimit(limit, MAX_LIST_LIMIT);
            int pageOffset = Math.max(0, offset);
            List<ScheduleConfiguration> schedules;
            if (agentId != null && !agentId.isBlank()) {
                schedules = scheduleStore.readSchedulesByAgentId(agentId, pageSize, pageOffset);
            } else {
                schedules = scheduleStore.readAllSchedules(pageSize, pageOffset);
            }
            // Redact HITL timeout schedules from non-admins so a plain editor
            // cannot enumerate hitl-timeout-* entries to locate and fire them.
            if (!ownershipValidator.isAdmin(identity)) {
                schedules = schedules.stream().filter(s -> !isHitlSchedule(s)).toList();
            }
            // Enrich with cron descriptions
            schedules.forEach(this::enrichCronDescription);
            return schedules;
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid schedule listing request: " + e.getMessage());
            throw new BadRequestException(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to read schedules", e);
            throw new InternalServerErrorException("Failed to read schedules");
        }
    }

    @Override
    public ScheduleConfiguration readSchedule(String scheduleId) {
        try {
            ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
            enrichCronDescription(schedule);
            return schedule;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to read schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to read schedule");
        }
    }

    @Override
    public Response createSchedule(ScheduleConfiguration schedule) {
        try {
            // G3: HITL timeout schedules are minted ONLY internally (ConversationService,
            // GroupConversationService, crash recovery — none of which go through REST).
            // Reject any request body carrying the hitl_timeout marker for EVERYONE
            // (even admins): a forged timeout schedule would let the poller
            // force-resume/abort a victim's pending approval with a system actor,
            // side-stepping the owner/admin/approver check on /resume.
            Response bodyGuard = rejectHitlTimeoutBody(schedule, "create");
            if (bodyGuard != null) {
                return bodyGuard;
            }

            // A schedule runs AS its userId — refuse to mint one that acts as
            // somebody else (see requireOwnUserId).
            Response ownerGuard = requireOwnUserId(schedule != null ? schedule.getUserId() : null, "create");
            if (ownerGuard != null) {
                return ownerGuard;
            }

            // A schedule converses with its agent on every fire, and the fire itself is
            // system-initiated — deliberately below the USE gate, because no interactive
            // caller exists then. The gate therefore applies HERE, where the human is:
            // without it, scheduling a private agent is a standing bypass of
            // /agents/{id}/start's check.
            if (schedule != null && schedule.getAgentId() != null && !schedule.getAgentId().isBlank()) {
                resourceAccessGuard.requireAgentUseAccess(schedule.getAgentId());
            }

            // Validate
            validateSchedule(schedule);

            // Apply trigger-type-aware defaults
            applyDefaults(schedule);

            // Stamp the creator from the authenticated identity rather than trusting
            // the body. createdBy exists to answer "who created this schedule" when a
            // rogue one keeps starting conversations, and nothing on this path ever
            // set it — every schedule created through the public API had it null.
            schedule.setCreatedBy(callerPrincipal());

            // Compute initial nextFire
            computeInitialNextFire(schedule);

            String id = scheduleStore.createSchedule(schedule);
            schedule.setId(id);
            enrichCronDescription(schedule);

            return Response.created(URI.create("/schedulestore/schedules/" + id)).entity(schedule).build();
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid schedule configuration: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid schedule configuration").build();
        } catch (ForbiddenException e) {
            // The USE gate's refusal. Without this rethrow the generic catch below
            // turns a clean 403 into a 500 — the caller cannot tell "you may not
            // schedule that agent" from "the server broke".
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to create schedule", e);
            throw new InternalServerErrorException("Failed to create schedule");
        }
    }

    @Override
    public Response updateSchedule(String scheduleId, ScheduleConfiguration schedule) {
        try {
            // G3: reject any request body that would CONVERT a schedule into a HITL
            // timeout (checked on the incoming body — for EVERYONE, even admins),
            // closing the create/update forgery path that side-steps the /resume gate.
            Response bodyGuard = rejectHitlTimeoutBody(schedule, "update");
            if (bodyGuard != null) {
                return bodyGuard;
            }

            // A HITL timeout schedule is a safety timer — a plain editor must not
            // be able to mutate it (e.g. push its nextFire far out to defeat an
            // ABORT/AUTO_REJECT deadline). Detect via the STORED schedule so a
            // request body that omits the metadata cannot bypass the check.
            Response guard = requireAdminForHitl(scheduleId, "update");
            if (guard != null) {
                return guard;
            }

            // Same USE gate as create: an update can re-point an existing schedule at a
            // different agent, which is the same act as scheduling that agent afresh.
            if (schedule != null && schedule.getAgentId() != null && !schedule.getAgentId().isBlank()) {
                resourceAccessGuard.requireAgentUseAccess(schedule.getAgentId());
            }

            // BOTH sides matter, and checking only one is the bug this replaces.
            //
            // STORED userId — "may I touch this schedule at all?". Guarding only the
            // body let a non-admin PUT over a schedule owned by victim-42 as long as
            // the body left userId unset (or "system:scheduler", which is exempt):
            // the guard passed and the update ran, so the caller could retarget the
            // agent/cron/message or effectively disarm the victim's dream schedule.
            // fireNow already checks the stored value for exactly this reason.
            //
            // BODY userId — "may I make it act as this identity?", i.e. the re-point
            // path that would aim an otherwise harmless schedule at another user.
            Response storedOwnerGuard = requireOwnUserIdOfStoredSchedule(scheduleId, "update");
            if (storedOwnerGuard != null) {
                return storedOwnerGuard;
            }

            Response ownerGuard = requireOwnUserId(schedule != null ? schedule.getUserId() : null, "update");
            if (ownerGuard != null) {
                return ownerGuard;
            }

            validateSchedule(schedule);

            // Same order as createSchedule, and for the same reason: PUT is a full
            // replace, so a field the body omits must be defaulted rather than
            // persisted empty. Update skipped this entirely, so an absent userId was
            // stored as null instead of SCHEDULER_USER_ID — and because ownership
            // treats null as unowned, an editor updating their OWN schedule silently
            // made it writable by every other editor.
            //
            // (The blank-timeZone half of that report was a different bug in a
            // different place: validateSchedule runs BEFORE this on both paths, and it
            // was validateSchedule's own ZoneId.of that threw. Fixed in zoneOf().)
            //
            // Deliberately AFTER the ownership guards above, so they judge what the
            // caller actually sent rather than what defaulting turned it into.
            applyDefaults(schedule);

            // A PUT edits CONFIGURATION; it must not rewrite provenance or the fire
            // lifecycle. Both are absent from the normal request shape (the Manager,
            // curl and MCP all send the editable fields only), so taking them from the
            // body meant an edit nulled createdAt/createdBy/lastFired and reset an
            // in-flight fire's state — re-opening a claim on a schedule that was
            // running. Carry them over from the stored row instead.
            carryOverNonEditableFields(scheduleId, schedule);

            // Recompute nextFire
            computeInitialNextFire(schedule);

            scheduleStore.updateSchedule(scheduleId, schedule);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid schedule update for " + scheduleId + ": " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid schedule configuration").build();
        } catch (ForbiddenException e) {
            // The USE gate's refusal. Without this rethrow the generic catch below
            // turns a clean 403 into a 500 — the caller cannot tell "you may not
            // schedule that agent" from "the server broke".
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to update schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to update schedule");
        }
    }

    @Override
    public Response deleteSchedule(String scheduleId) {
        try {
            // Deleting a HITL timeout schedule disarms a safety deadline — restrict
            // to admins so an editor cannot leave a paused conversation with no
            // ABORT/AUTO_REJECT resolution. The conventional cleanup path is
            // POST /agents/{conversationId}/resume or .../cancel.
            Response guard = requireAdminForHitl(scheduleId, "delete");
            if (guard != null) {
                return guard;
            }
            scheduleStore.deleteSchedule(scheduleId);
            return Response.noContent().build();
        } catch (Exception e) {
            LOGGER.error("Failed to delete schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to delete schedule");
        }
    }

    @Override
    public Response enableSchedule(String scheduleId) {
        Response guard = requireAdminForHitl(scheduleId, "enable");
        if (guard != null) {
            return guard;
        }
        return setEnabled(scheduleId, true);
    }

    @Override
    public Response disableSchedule(String scheduleId) {
        // Disabling a HITL timeout schedule is equivalent to disarming it.
        Response guard = requireAdminForHitl(scheduleId, "disable");
        if (guard != null) {
            return guard;
        }
        return setEnabled(scheduleId, false);
    }

    @Override
    public Response fireNow(String scheduleId) {
        try {
            ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
            // A HITL timeout schedule fires the configured AUTO_APPROVE/AUTO_REJECT/
            // ABORT decision with a system actor and NO owner/admin/approver check.
            // Manually firing it here would side-step the authorization gate on
            // POST /agents/{conversationId}/resume, so refuse for EVERYONE — the
            // human decision must go through /resume or /cancel. Internal firing
            // via SchedulePollerService bypasses this REST surface and is unaffected.
            if (isHitlSchedule(schedule)) {
                LOGGER.warnf("Refused manual fire of HITL timeout schedule %s (name=%s) — "
                        + "human decisions must go through /resume or /cancel", sanitize(scheduleId), sanitize(schedule.getName()));
                return Response.status(Response.Status.CONFLICT)
                        .entity("This schedule is a human-in-the-loop approval timeout and cannot be fired manually. "
                                + "Approve or reject via POST /agents/{conversationId}/resume, "
                                + "or terminate via POST /agents/{conversationId}/cancel.")
                        .build();
            }
            // Checked on the STORED schedule: firing one that acts as another user is
            // the step that actually executes as them — including the
            // dreamType=dream_consolidation fast-path, which prunes and rewrites that
            // user's persistent memories.
            Response ownerGuard = requireOwnUserId(schedule.getUserId(), "fire");
            if (ownerGuard != null) {
                return ownerGuard;
            }
            // Claim it first, on the poller's own terms. Without a claim a manual fire
            // ran concurrently with the poller's fire of the same schedule — and with
            // conversationStrategy=persistent both pushed a turn into the SAME
            // conversation. A refused claim is a conflict, not a server error — but
            // WHY it was refused matters to the operator, so say which state blocked
            // it (see manualFireRefusalReason).
            if (!pollerService.claimForManualFire(schedule)) {
                LOGGER.warnf("Refused manual fire of schedule %s — could not claim it (fireStatus=%s)",
                        sanitize(scheduleId), schedule.getFireStatus());
                return Response.status(Response.Status.CONFLICT)
                        .entity(manualFireRefusalReason(schedule))
                        .build();
            }
            // The attempt this actually is, not a constant 1: a schedule already on its
            // third failed attempt used to log a manual retry as "attempt 1", so the
            // fire log read as a fresh first try and hid the failure history.
            int attemptNumber = schedule.getFailCount() + 1;
            ScheduleFireLog fireLog = null;
            try {
                fireLog = fireExecutor.fire(schedule, pollerService.getInstanceId(), attemptNumber);
            } finally {
                // Always release the claim, whatever happened — a manual fire that left
                // the row CLAIMED would block the poller until the lease expired.
                //
                // Park the interrupt across that write, exactly as
                // SchedulePollerService.fireClaimedSchedule does. ScheduleFireExecutor
                // re-asserts a consumed interrupt before returning, so on the interrupted
                // path (client disconnect, shutdown, RESTEasy cancellation) this block
                // would otherwise run with the flag set — and the synchronous Mongo driver
                // throws MongoInterruptedException on connection checkout.
                // recordManualFireOutcome swallows that, so the claim would never be
                // released and failCount never incremented: precisely the leak this
                // finally exists to prevent, self-healing only once the lease expires. The
                // flag is re-asserted immediately afterwards so the cancellation signal
                // still reaches the caller.
                boolean wasInterrupted = Thread.interrupted();
                try {
                    pollerService.recordManualFireOutcome(schedule, fireLog);
                } finally {
                    if (wasInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return Response.ok(fireLog).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to manually fire schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to fire schedule");
        }
    }

    @Override
    public List<ScheduleFireLog> readFireLogs(String scheduleId, int limit) {
        try {
            return scheduleStore.readFireLogs(scheduleId, boundedLimit(limit, MAX_FIRE_LOG_LIMIT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid fire log limit: " + e.getMessage());
            throw new BadRequestException(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to read fire logs for schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to read fire logs");
        }
    }

    @Override
    public List<ScheduleFireLog> readFailedFires(int limit) {
        try {
            return scheduleStore.readFailedFireLogs(boundedLimit(limit, MAX_FIRE_LOG_LIMIT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid fire log limit: " + e.getMessage());
            throw new BadRequestException(e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to read failed fires", e);
            throw new InternalServerErrorException("Failed to read failed fires");
        }
    }

    /**
     * Validate and cap a caller-supplied {@code limit}.
     * <p>
     * The value used to be passed straight through, and the two backends read it
     * differently: the MongoDB driver treats {@code limit(0)} as "no limit" while
     * PostgreSQL's {@code LIMIT 0} returns nothing, so {@code ?limit=0} dumped
     * every fire log ever written on one backend and an empty list on the other. An
     * unbounded positive value was accepted on both. Rejecting non-positive values
     * and capping the rest gives the parameter one meaning everywhere.
     */
    private static int boundedLimit(int limit, int max) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(limit, max);
    }

    @Override
    public Response retryDeadLetter(String scheduleId) {
        try {
            // A HITL timeout schedule is a safety timer: requeuing it re-arms the
            // AUTO_REJECT/AUTO_APPROVE/ABORT decision on a system actor. Like every
            // other HITL mutation (update/delete/enable/disable), restrict it to
            // admins so an editor cannot manipulate another user's pending approval.
            Response guard = requireAdminForHitl(scheduleId, "retry");
            if (guard != null) {
                return guard;
            }
            scheduleStore.requeueDeadLetter(scheduleId);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found or not dead-lettered: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to retry dead-letter " + scheduleId, e);
            throw new InternalServerErrorException("Failed to retry dead-letter");
        }
    }

    // Fix #8: dismissDeadLetter uses markCompleted with proper nextFire recompute
    @Override
    public Response dismissDeadLetter(String scheduleId) {
        try {
            // Dismissing a one-shot HITL timeout schedule disarms it permanently
            // (markCompleted with a null nextFire disables it) — exactly the
            // "editor cannot disarm an ABORT/AUTO_REJECT safety timeout" guarantee the
            // other mutation paths enforce. Require admin here too.
            Response guard = requireAdminForHitl(scheduleId, "dismiss");
            if (guard != null) {
                return guard;
            }
            ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
            Instant nextFire = computeNextFireForSchedule(schedule);
            scheduleStore.markCompleted(scheduleId, nextFire);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to dismiss dead-letter " + scheduleId, e);
            throw new InternalServerErrorException("Failed to dismiss dead-letter");
        }
    }

    // --- Helpers ---

    /**
     * Why a manual fire could not claim the schedule, phrased for the operator who
     * just pressed "Fire now".
     * <p>
     * {@code tryClaim} accepts exactly three states — PENDING, FAILED whose
     * {@code nextRetryAt} has fallen due, and a CLAIMED row whose lease has expired
     * — so a refusal is by no means always "someone else is firing it". A schedule
     * still waiting out its retry backoff, or one that has been dead-lettered, is
     * refused too, and those are precisely the states an operator reaches for the
     * manual fire from. Answering all three with "already being fired" sends them
     * hunting for a fire that does not exist, and hides the one endpoint that would
     * actually help.
     */
    private static String manualFireRefusalReason(ScheduleConfiguration schedule) {
        FireStatus status = schedule != null ? schedule.getFireStatus() : null;
        if (FireStatus.DEAD_LETTERED == status) {
            return "This schedule is dead-lettered after " + schedule.getFailCount()
                    + " failed attempt(s) and will not fire until it is requeued. "
                    + "Use POST /schedulestore/schedules/{id}/retry.";
        }
        if (FireStatus.FAILED == status) {
            return "This schedule is in the FAILED state and its retry is not due yet"
                    + (schedule.getNextRetryAt() != null ? " (next retry at " + schedule.getNextRetryAt() + ")" : "")
                    + ". Use POST /schedulestore/schedules/{id}/retry to clear the failure state, "
                    + "or wait for the retry to fall due.";
        }
        return "This schedule is already being fired (claimed by another instance or the poller). Try again shortly.";
    }

    /**
     * Copy the fields a PUT may not edit from the stored schedule onto the incoming
     * body: provenance ({@code createdAt}, {@code createdBy}), fire history
     * ({@code lastFired}), the claim/retry lifecycle ({@code fireStatus},
     * {@code failCount}, {@code claimedBy}, {@code claimedAt}, {@code fireId},
     * {@code nextRetryAt}) and {@code persistentConversationId}.
     * <p>
     * Two things went wrong without this. On MongoDB the update was a
     * whole-document replace, so every one of these read back null after any edit
     * that did not echo them — the audit trail simply vanished. And on BOTH
     * backends {@code fireStatus} was taken from the body, defaulting to PENDING
     * when absent: editing a schedule while it was firing un-claimed the running
     * fire and let the next poll claim and fire it a second time.
     * <p>
     * Recovering a FAILED or DEAD_LETTERED schedule is therefore deliberately NOT a
     * side effect of editing it: {@code POST /schedules/{id}/enable} and
     * {@code POST /schedules/{id}/retry} both clear the failure state explicitly.
     * <p>
     * A schedule that is NOT FOUND is left alone rather than failing here — the
     * store's own update call is about to surface the 404. Every other store
     * failure aborts the update instead of being logged and shrugged off: without
     * the stored row this method has nothing to carry over, and
     * {@link #applyDefaults} has already stamped {@code fireStatus = PENDING} onto
     * the body — so proceeding would write exactly the mid-fire un-claim this
     * method exists to prevent, on nothing worse than a transient store blip.
     */
    private void carryOverNonEditableFields(String scheduleId, ScheduleConfiguration schedule)
            throws IResourceStore.ResourceStoreException {
        ScheduleConfiguration stored;
        try {
            stored = scheduleStore.readSchedule(scheduleId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warn("Stored schedule " + sanitize(scheduleId) + " not found while preserving its non-editable fields", e);
            return;
        }
        if (stored == null) {
            return;
        }
        schedule.setCreatedAt(stored.getCreatedAt());
        schedule.setCreatedBy(stored.getCreatedBy());
        schedule.setLastFired(stored.getLastFired());
        schedule.setFireStatus(stored.getFireStatus());
        schedule.setFailCount(stored.getFailCount());
        schedule.setClaimedBy(stored.getClaimedBy());
        schedule.setClaimedAt(stored.getClaimedAt());
        schedule.setFireId(stored.getFireId());
        schedule.setNextRetryAt(stored.getNextRetryAt());
        schedule.setPersistentConversationId(stored.getPersistentConversationId());
    }

    /**
     * The authenticated caller's principal name, or {@value #SCHEDULER_USER_ID}
     * when there is none (authorization disabled, or a system-initiated call).
     * Never throws — an audit stamp must not be able to fail a create.
     */
    private String callerPrincipal() {
        try {
            var principal = identity != null ? identity.getPrincipal() : null;
            String name = principal != null ? principal.getName() : null;
            return name != null && !name.isBlank() ? name : SCHEDULER_USER_ID;
        } catch (RuntimeException e) {
            return SCHEDULER_USER_ID;
        }
    }

    /** True if the schedule carries the HITL approval-timeout metadata marker. */
    private static boolean isHitlSchedule(ScheduleConfiguration schedule) {
        Map<String, Object> md = schedule != null ? schedule.getMetadata() : null;
        return md != null && HITL_TYPE_TIMEOUT.equals(md.get(HITL_TYPE_KEY));
    }

    /**
     * G3: refuses a create/update whose request BODY declares the schedule as a
     * HITL approval timeout. These schedules are only ever minted internally (they
     * bypass REST), so no REST caller — admin or editor — has a legitimate reason
     * to forge one; letting them would hand an attacker a way to force-resume/abort
     * another user's pending approval with a system actor and no authorization
     * check.
     *
     * @return a 400 {@link Response} to short-circuit the caller when the body is a
     *         HITL timeout schedule; {@code null} when the operation may proceed
     */
    private Response rejectHitlTimeoutBody(ScheduleConfiguration schedule, String operation) {
        if (isHitlSchedule(schedule)) {
            LOGGER.warnf("Refused %s of a schedule whose body declares hitlType=%s — "
                    + "HITL timeout schedules are minted internally only", operation, HITL_TYPE_TIMEOUT);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Schedules marked as human-in-the-loop approval timeouts (metadata hitlType="
                            + HITL_TYPE_TIMEOUT + ") cannot be created or updated via this API. "
                            + "They are managed automatically by the HITL framework. "
                            + "Resolve a pending approval via POST /agents/{conversationId}/resume or .../cancel.")
                    .build();
        }
        return null;
    }

    /**
     * A schedule's {@code userId} is the identity every fire ACTS AS: it becomes
     * the owner of the conversation the fire starts, and for a
     * {@code dreamType=dream_consolidation} schedule it is the user whose
     * persistent memories {@code DreamService} prunes, rewrites and permanently
     * deletes. The direct memory API ({@code IRestUserMemoryStore}) refuses a
     * non-admin access to another user's memories on every method, so this surface
     * must not become a back door around it: a non-admin may only create, re-point
     * or manually fire a schedule that runs as themselves, or as the unowned
     * {@value #SCHEDULER_USER_ID} placeholder (which Dream consolidation explicitly
     * rejects).
     * <p>
     * Deliberately refuses instead of silently rewriting {@code userId} to the
     * caller: a rewrite would hand back a schedule that does something other than
     * what was asked for, and would hide the attempt. Blank/absent and placeholder
     * identities are left alone, so existing stored schedules and system schedules
     * keep working unchanged. All checks are no-ops when
     * {@code authorization.enabled=false}.
     *
     * @param userId
     *            the identity the schedule would run as (may be {@code null})
     * @param operation
     *            the operation name, for the log line and error message
     * @return a 403 {@link Response} to short-circuit the caller, or {@code null}
     *         when the operation may proceed
     */
    private Response requireOwnUserId(String userId, String operation) {
        if (userId == null || userId.isBlank() || SCHEDULER_USER_ID.equals(userId)) {
            return null; // no end-user identity to act as
        }
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isOwner(identity, userId)) {
            return null;
        }
        LOGGER.warnf("Refused %s of a schedule running as another user by a non-admin caller", sanitize(operation));
        LOGGER.debugf("Schedule ownership detail: operation='%s', userId='%s'", sanitize(operation), sanitize(userId));
        return Response.status(Response.Status.FORBIDDEN)
                .entity("A schedule may only run as yourself: set userId to your own identity, or leave it "
                        + "unset to run as the system scheduler. Only an administrator may " + operation
                        + " a schedule that runs as another user.")
                .build();
    }

    /**
     * The other half of {@link #requireOwnUserId}: may this caller touch the
     * schedule that is <em>already stored</em> under {@code scheduleId}?
     * <p>
     * Checking the request body alone is not enough. A body that simply omits
     * {@code userId} is exempt (it means "run as the system scheduler"), so a
     * body-only guard let a non-admin overwrite a schedule stored against another
     * user — retargeting its agent, cron or message, or disarming it outright.
     * {@code fireNow} reads the stored value for the same reason.
     * <p>
     * A missing schedule is left to the caller's own not-found handling rather than
     * being reported as forbidden, so this guard cannot be used to probe which
     * schedule ids exist.
     * <p>
     * "Not found" and "could not read it" are deliberately NOT the same outcome. An
     * earlier version caught {@code Exception} and returned "allow" for both,
     * collapsing two very different causes into one benign answer on a security
     * check.
     * <p>
     * On the update path that was not actually exploitable —
     * {@link #requireAdminForHitl} reads the same schedule first and already fails
     * closed — but a guard whose safety depends on an unrelated guard running
     * before it is one reordering away from being a hole, so this one fails closed
     * on its own account. It mirrors that method's status and phrasing rather than
     * inventing a second convention for the same condition.
     */
    private Response requireOwnUserIdOfStoredSchedule(String scheduleId, String operation) {
        ScheduleConfiguration stored;
        try {
            stored = scheduleStore.readSchedule(scheduleId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null; // no schedule to protect — let the downstream op surface its 404
        } catch (Exception e) {
            LOGGER.error("Failed to verify schedule ownership for " + sanitize(scheduleId) + " (" + sanitize(operation) + ")", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Unable to verify schedule authorization; refusing to " + operation + " schedule.")
                    .build();
        }
        if (stored == null) {
            return null;
        }
        return requireOwnUserId(stored.getUserId(), operation);
    }

    /**
     * For mutating operations on a HITL timeout schedule, require the eddi-admin
     * role. Reads the STORED schedule so a request body cannot hide the marker. The
     * guard fails CLOSED: only a genuine not-found falls through (so the downstream
     * op surfaces its own 404); any other read/verification failure returns 500 and
     * STOPS the operation, so a transient store error can never let a non-admin
     * mutate a schedule that might be a HITL safety timeout.
     *
     * @return a 403/500 {@link Response} to short-circuit the caller when the
     *         target is (or cannot be proven not to be) a HITL schedule;
     *         {@code null} only when the schedule is confirmed non-HITL or
     *         genuinely absent
     */
    private Response requireAdminForHitl(String scheduleId, String operation) {
        ScheduleConfiguration stored;
        try {
            stored = scheduleStore.readSchedule(scheduleId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return null; // no schedule to protect — let the downstream op surface its 404
        } catch (Exception e) {
            // Fail closed: we could not prove this is NOT a HITL safety timeout, so we
            // must not let the mutation proceed unauthenticated.
            LOGGER.error("Failed to verify HITL guard for schedule " + sanitize(scheduleId) + " (" + sanitize(operation) + ")", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Unable to verify schedule authorization; refusing to " + operation + " schedule.")
                    .build();
        }
        if (isHitlSchedule(stored) && !ownershipValidator.isAdmin(identity)) {
            LOGGER.warnf("Refused %s of HITL timeout schedule %s (name=%s) by non-admin",
                    sanitize(operation), sanitize(scheduleId), sanitize(stored.getName()));
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("This schedule is a human-in-the-loop approval timeout; only an administrator may " + operation
                            + " it. The pending approval is resolved via POST /agents/{conversationId}/resume or .../cancel.")
                    .build();
        }
        return null;
    }

    // Fix #3: Atomic enable/disable instead of read-then-write race
    private Response setEnabled(String scheduleId, boolean enabled) {
        try {
            Instant nextFire = null;
            if (enabled) {
                // Need to read schedule to compute nextFire
                ScheduleConfiguration schedule = scheduleStore.readSchedule(scheduleId);
                nextFire = computeRearmNextFire(schedule);
            }
            scheduleStore.setScheduleEnabled(scheduleId, enabled, nextFire);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Schedule not found: " + scheduleId);
        } catch (Exception e) {
            LOGGER.error("Failed to set enabled=" + enabled + " for schedule " + scheduleId, e);
            throw new InternalServerErrorException("Failed to update schedule");
        }
    }

    /**
     * The schedule's time zone, or the configured default when it is absent.
     * <p>
     * "Absent" means null OR blank. The three {@code ZoneId.of} call sites used to
     * disagree about this: one passed the raw value, two null-checked but not blank
     * — and a blank string is non-null, so it reached {@code ZoneId.of("")} and
     * threw {@code DateTimeException}. That surfaced as a 500 on both create and
     * update for a body carrying {@code "timeZone": ""}, even though
     * {@link #applyDefaults} treats blank as "use the default" and
     * {@link #validateSchedule} deliberately skips validating a blank value. One
     * accessor, one definition of absent.
     */
    private ZoneId zoneOf(ScheduleConfiguration schedule) {
        String zone = schedule.getTimeZone();
        return ZoneId.of(zone == null || zone.isBlank() ? defaultTimeZone : zone);
    }

    private void applyDefaults(ScheduleConfiguration schedule) {
        // Infer trigger type from fields — must also handle the case where the
        // default CRON value is set but heartbeatIntervalSeconds indicates HEARTBEAT
        if (schedule.getTriggerType() == null || schedule.getHeartbeatIntervalSeconds() != null) {
            if (schedule.getHeartbeatIntervalSeconds() != null) {
                schedule.setTriggerType(TriggerType.HEARTBEAT);
            } else {
                schedule.setTriggerType(TriggerType.CRON);
            }
        }

        if (schedule.getEnvironment() == null || schedule.getEnvironment().isBlank()) {
            schedule.setEnvironment("production");
        }
        if (schedule.getUserId() == null || schedule.getUserId().isBlank()) {
            schedule.setUserId(SCHEDULER_USER_ID);
        }
        if (schedule.getConversationStrategy() == null || schedule.getConversationStrategy().isBlank()) {
            // Heartbeats default to persistent, cron to new
            schedule.setConversationStrategy(schedule.getTriggerType() == TriggerType.HEARTBEAT ? "persistent" : "new");
        }
        if (schedule.getTimeZone() == null || schedule.getTimeZone().isBlank()) {
            schedule.setTimeZone(defaultTimeZone);
        }
        if (schedule.getFireStatus() == null) {
            schedule.setFireStatus(FireStatus.PENDING);
        }
        // Heartbeat: default message if unset
        if (schedule.getTriggerType() == TriggerType.HEARTBEAT && (schedule.getMessage() == null || schedule.getMessage().isBlank())) {
            schedule.setMessage("heartbeat");
        }
    }

    private void computeInitialNextFire(ScheduleConfiguration schedule) {
        Instant nextFire = computeRearmNextFire(schedule);
        if (nextFire != null) {
            schedule.setNextFire(nextFire);
        }
    }

    /**
     * The next fire of a <em>recurring</em> schedule, or {@code null} when it has
     * no recurrence.
     * <p>
     * {@code oneTimeAt} is deliberately absent: this feeds {@code markCompleted},
     * where {@code null} is the signal that a one-shot is finished and must be
     * disabled. Answering with a one-shot's (now past) instant here would re-arm
     * the schedule to fire again immediately. Arming a one-shot is
     * {@link #computeRearmNextFire}'s job.
     */
    private Instant computeNextFireForSchedule(ScheduleConfiguration schedule) {
        if (schedule.getTriggerType() == TriggerType.HEARTBEAT && schedule.getHeartbeatIntervalSeconds() != null) {
            return Instant.now().plusSeconds(schedule.getHeartbeatIntervalSeconds());
        }
        if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
            return computeNextFire(schedule);
        }
        return null;
    }

    /**
     * {@link CronParser#computeNextFire} for a schedule, with the "syntactically
     * valid but can never match" case (e.g. {@code 0 0 30 2 *} — February 30th)
     * reported as a client error. The parser signals it with
     * {@link IllegalStateException}, which is not an
     * {@link IllegalArgumentException}, so it used to escape as a 500.
     */
    private Instant computeNextFire(ScheduleConfiguration schedule) {
        try {
            return CronParser.computeNextFire(schedule.getCronExpression(), Instant.now(), zoneOf(schedule));
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(
                    "cronExpression '" + schedule.getCronExpression() + "' has no matching fire time within the next two years", e);
        }
    }

    /** See {@link #computeNextFire} — same unsatisfiable-cron translation. */
    private long computeMinIntervalSeconds(ScheduleConfiguration schedule) {
        try {
            return CronParser.computeMinIntervalSeconds(schedule.getCronExpression(), zoneOf(schedule));
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(
                    "cronExpression '" + schedule.getCronExpression() + "' has no matching fire time within the next two years", e);
        }
    }

    /**
     * The instant a schedule should next fire when it is being (re-)armed — on
     * create, on update, and on {@code POST /schedules/{id}/enable}.
     * <p>
     * Unlike {@link #computeNextFireForSchedule} this handles a one-shot
     * ({@code oneTimeAt}) schedule, and that is the whole point. Enabling used to
     * fall through to {@code null} for one-shots, and both stores skip the re-arm
     * when nextFire is null — so the endpoint answered 200, the row read back
     * {@code enabled=true}, and {@code nextFire} stayed NULL, which
     * {@code findDueSchedules} can never match ({@code next_fire <= ?} is UNKNOWN
     * for NULL on Postgres, and BSON type bracketing excludes null on Mongo). The
     * schedule sat enabled-but-dead forever, with no error anywhere.
     * <p>
     * A {@code oneTimeAt} that has already passed arms for {@code now} rather than
     * the past instant, so a re-enabled one-shot fires on the next poll instead of
     * relying on {@code <=} semantics against a stale timestamp.
     */
    private Instant computeRearmNextFire(ScheduleConfiguration schedule) {
        Instant recurring = computeNextFireForSchedule(schedule);
        if (recurring != null) {
            return recurring;
        }
        if (schedule.getOneTimeAt() != null && !schedule.getOneTimeAt().isBlank()) {
            Instant at = parseOneTimeAt(schedule.getOneTimeAt());
            Instant now = Instant.now();
            return at.isBefore(now) ? now : at;
        }
        return null;
    }

    /**
     * Parse a {@code oneTimeAt} value, reporting a malformed one as a client error.
     * <p>
     * {@link Instant#parse} throws {@link DateTimeParseException}, which is a
     * {@link java.time.DateTimeException} and NOT an
     * {@link IllegalArgumentException} — so it slipped past the 400 handler on
     * create/update and surfaced as a 500 "Failed to create schedule" with a stack
     * trace, for nothing worse than a typo such as {@code "2026-09-03 10:00"}.
     */
    private static Instant parseOneTimeAt(String oneTimeAt) {
        try {
            return Instant.parse(oneTimeAt);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("oneTimeAt must be an ISO-8601 instant (e.g. 2026-09-03T10:00:00Z), got: " + oneTimeAt, e);
        }
    }

    private void validateSchedule(ScheduleConfiguration schedule) {
        // An empty POST/PUT body deserializes to null. Dereferencing it produced an
        // NPE and a 500 for what is plainly a client mistake.
        if (schedule == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (schedule.getAgentId() == null || schedule.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }

        // Validate time zone early — before cron parsing which also uses ZoneId.of()
        if (schedule.getTimeZone() != null && !schedule.getTimeZone().isBlank()) {
            try {
                ZoneId.of(schedule.getTimeZone());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid time zone: " + schedule.getTimeZone());
            }
        }

        // Infer trigger type: if heartbeatIntervalSeconds is set, treat as HEARTBEAT
        // regardless of the default value in ScheduleConfiguration
        TriggerType type = schedule.getTriggerType();
        if (type == null || schedule.getHeartbeatIntervalSeconds() != null) {
            type = schedule.getHeartbeatIntervalSeconds() != null ? TriggerType.HEARTBEAT : TriggerType.CRON;
        }

        if (type == TriggerType.HEARTBEAT) {
            // Heartbeat: must have interval
            if (schedule.getHeartbeatIntervalSeconds() == null || schedule.getHeartbeatIntervalSeconds() <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalSeconds is required and must be > 0 for HEARTBEAT triggers");
            }
            // Enforce minimum interval
            if (schedule.getHeartbeatIntervalSeconds() < minIntervalSeconds) {
                throw new IllegalArgumentException(String.format("Heartbeat interval (%ds) is below minimum allowed (%ds)",
                        schedule.getHeartbeatIntervalSeconds(), minIntervalSeconds));
            }
            // Message is optional for heartbeats (will default to "heartbeat")
        } else {
            // CRON type: validate message required
            if (schedule.getMessage() == null || schedule.getMessage().isBlank()) {
                throw new IllegalArgumentException("message is required for CRON triggers");
            }

            // Exactly one of cron or oneTimeAt
            boolean hasCron = schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank();
            boolean hasOneTime = schedule.getOneTimeAt() != null && !schedule.getOneTimeAt().isBlank();
            if (!hasCron && !hasOneTime) {
                throw new IllegalArgumentException("Either cronExpression or oneTimeAt is required for CRON triggers");
            }
            if (hasCron && hasOneTime) {
                throw new IllegalArgumentException("Cannot set both cronExpression and oneTimeAt");
            }

            // A malformed one-shot instant is a client error, not a server error.
            if (hasOneTime) {
                parseOneTimeAt(schedule.getOneTimeAt());
            }

            // Validate cron
            if (hasCron) {
                CronParser.validate(schedule.getCronExpression());

                // Enforce minimum interval
                long intervalSec = computeMinIntervalSeconds(schedule);
                if (intervalSec < minIntervalSeconds) {
                    throw new IllegalArgumentException(String.format(
                            "Cron interval (%ds) is below minimum allowed (%ds). "
                                    + "Use a less frequent schedule or contact admin to adjust eddi.schedule.min-interval-seconds",
                            intervalSec, minIntervalSeconds));
                }
            }
        }

        // Validate conversation strategy
        String strategy = schedule.getConversationStrategy();
        if (strategy != null && !strategy.isBlank() && !strategy.equals("new") && !strategy.equals("persistent")) {
            throw new IllegalArgumentException("conversationStrategy must be 'new' or 'persistent', got: " + strategy);
        }
    }

    private void enrichCronDescription(ScheduleConfiguration schedule) {
        if (schedule.getCronExpression() != null && !schedule.getCronExpression().isBlank()) {
            schedule.setCronDescription(CronDescriber.describe(schedule.getCronExpression()));
        } else if (schedule.getTriggerType() == TriggerType.HEARTBEAT && schedule.getHeartbeatIntervalSeconds() != null) {
            schedule.setCronDescription(describeHeartbeat(schedule.getHeartbeatIntervalSeconds()));
        }
    }

    /**
     * Human-readable heartbeat interval.
     * <p>
     * A coarser unit is only used when the interval divides into it exactly.
     * Truncating integer division described 90 s as "Every minute", 5400 s as
     * "Every hour" and 129600 s as "Every day" — telling an operator the agent
     * fires 1.5x more often than it does.
     */
    private static String describeHeartbeat(long seconds) {
        if (seconds < 60 || seconds % 60 != 0) {
            return "Every " + seconds + " seconds";
        }
        long minutes = seconds / 60;
        if (minutes < 60 || minutes % 60 != 0) {
            return minutes == 1 ? "Every minute" : "Every " + minutes + " minutes";
        }
        long hours = minutes / 60;
        if (hours < 24 || hours % 24 != 0) {
            return hours == 1 ? "Every hour" : "Every " + hours + " hours";
        }
        long days = hours / 24;
        return days == 1 ? "Every day" : "Every " + days + " days";
    }
}
