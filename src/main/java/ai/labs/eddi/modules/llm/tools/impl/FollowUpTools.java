/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code schedule_followup}, {@code list_followups} and
 * {@code cancel_followup}: an agent books a one-off check-back for itself — a
 * message delivered at a time it names, to this conversation or to a fresh one
 * with the same agent — through the ordinary one-shot schedule the scheduler
 * already fires ({@code ScheduleFireExecutor}), so the fire is logged, retried
 * and dead-lettered like any other.
 * <p>
 * Bounds, all enforced here rather than trusted to the model: the time must be
 * at least a minute away and within {@code maxHorizonDays} (never more than
 * 90); an agent may have {@code maxPending} check-backs waiting per booking
 * user and book {@code maxPerTurn} in one turn; and every fire carries
 * {@code maxCostPerFireUsd} as its {@code maxCostPerFire}, so a booked
 * check-back is never unlimited. A check-back is the creating user's: it fires
 * as that user, an agent can list and cancel only the ones it booked, and in a
 * group discussion (whose member conversations are internal) only the
 * new-conversation target is offered.
 *
 * @since 6.7.0
 */
@Vetoed
public class FollowUpTools {

    private static final Logger LOGGER = Logger.getLogger(FollowUpTools.class);

    public static final String SCHEDULE_TOOL = "schedule_followup";
    public static final String LIST_TOOL = "list_followups";
    public static final String CANCEL_TOOL = "cancel_followup";

    /**
     * {@code metadata.followupType} marking a schedule as an agent's check-back.
     */
    public static final String METADATA_TYPE_KEY = "followupType";
    public static final String METADATA_TYPE_VALUE = "agent_followup";
    static final String METADATA_BOOKED_BY = "bookedByAgentId";
    static final String METADATA_CONVERSATION = "conversationId";
    static final String NAME_PREFIX = "agent-followup-";

    static final int MIN_LEAD_SECONDS = 60;
    static final int MAX_MESSAGE_CHARS = 2000;
    static final int SCAN_LIMIT = 500;
    /** Bounds the scan at 20,000 schedules per agent, so a read cannot run away. */
    static final int MAX_SCAN_PAGES = 40;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the booking is made for: the identity of the conversation asking. */
    public record Booker(String agentId, String userId, String conversationId, boolean inGroupDiscussion, List<String> creatorTeams) {
        public Booker {
            creatorTeams = creatorTeams == null ? List.of() : List.copyOf(creatorTeams);
        }
    }

    private final Booker booker;
    private final AgentOpsConfig.FollowUp limits;
    private final IScheduleStore scheduleStore;
    private final Clock clock;
    private final AtomicInteger booked = new AtomicInteger();

    public FollowUpTools(Booker booker, AgentOpsConfig.FollowUp limits, IScheduleStore scheduleStore, Clock clock) {
        this.booker = booker;
        this.limits = limits != null ? limits : new AgentOpsConfig.FollowUp();
        this.scheduleStore = scheduleStore;
        this.clock = clock;
    }

    @Tool(name = SCHEDULE_TOOL, value = "Book a one-off check-back for yourself: at the time you give, the message you write is sent to "
            + "you again, so you can look at something once it has had time to change (a metric, an approval, a task). It is "
            + "not a recurring job. List your waiting check-backs first so you do not book the same one twice.")
    public String scheduleFollowup(@P("When, as an ISO-8601 instant with a zone, e.g. 2026-11-10T09:00:00Z. At least a minute ahead and within the allowed horizon.") String at,
                                   @P("What to tell yourself when it fires: what to check and what to do about the result") String message,
                                   @P(value = "'conversation' continues this conversation (default); 'agent' starts a fresh one with the same agent. In a team discussion only 'agent' is available.",
                                      required = false) String target) {
        if (booked.get() >= limits.effectiveMaxPerTurn()) {
            return perTurnRefusal();
        }
        if (message == null || message.isBlank()) {
            return refused("A message is required: say what to check when it fires.");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            return refused("The message is longer than " + MAX_MESSAGE_CHARS + " characters; shorten it.");
        }
        boolean ownConversation = target == null || target.isBlank() || "conversation".equalsIgnoreCase(target.strip());
        if (!ownConversation && !"agent".equalsIgnoreCase(target.strip())) {
            return refused("target must be 'conversation' or 'agent'.");
        }
        if (ownConversation && (booker.inGroupDiscussion() || isBlank(booker.conversationId()))) {
            return refused("This conversation cannot be continued by a check-back; use target 'agent' to start a fresh conversation.");
        }
        if (isBlank(booker.agentId()) || isBlank(booker.userId())) {
            return refused("A check-back needs a known agent and user; this conversation has neither.");
        }
        Instant when;
        try {
            when = parse(at);
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        }
        Instant now = clock.instant();
        if (when.isBefore(now.plusSeconds(MIN_LEAD_SECONDS))) {
            return refused("The time must be at least a minute from now.");
        }
        Instant horizon = now.plus(Duration.ofDays(limits.effectiveMaxHorizonDays()));
        if (when.isAfter(horizon)) {
            return refused("A check-back may be booked at most " + limits.effectiveMaxHorizonDays() + " days ahead (until " + horizon + ").");
        }
        // The per-turn slot is reserved only now, when a booking is about to be
        // written, and released unless it is: a refused call must not use up the
        // turn's quota. Reserve-then-check keeps it exact under parallel tool calls.
        if (booked.incrementAndGet() > limits.effectiveMaxPerTurn()) {
            booked.decrementAndGet();
            return perTurnRefusal();
        }
        boolean written = false;
        try {
            if (pending().size() >= limits.effectiveMaxPending()) {
                return refused("You already have " + limits.effectiveMaxPending()
                        + " check-backs waiting. Cancel one with cancel_followup, or wait for one to fire.");
            }
            ScheduleConfiguration schedule = new ScheduleConfiguration();
            schedule.setName(NAME_PREFIX + booker.agentId() + "-" + UUID.randomUUID().toString().substring(0, 8));
            schedule.setAgentId(booker.agentId());
            schedule.setEnvironment("production");
            schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
            schedule.setOneTimeAt(when.toString());
            schedule.setNextFire(when);
            schedule.setTimeZone("UTC");
            schedule.setMessage(message.strip());
            schedule.setUserId(booker.userId());
            schedule.setCreatedBy(booker.userId());
            schedule.setCreatorTeams(booker.creatorTeams());
            // Never an administrator's fire: the check-back may use what the creator
            // could use, and nothing it could not.
            schedule.setCreatorAdmin(false);
            schedule.setEnabled(true);
            schedule.setMaxCostPerFire(limits.effectiveMaxCostPerFireUsd());
            schedule.setCreatedAt(now);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(METADATA_TYPE_KEY, METADATA_TYPE_VALUE);
            metadata.put(METADATA_BOOKED_BY, booker.agentId());
            if (ownConversation) {
                schedule.setConversationStrategy("persistent");
                schedule.setPersistentConversationId(booker.conversationId());
                metadata.put(METADATA_CONVERSATION, booker.conversationId());
            } else {
                schedule.setConversationStrategy("new");
            }
            schedule.setMetadata(metadata);
            String id = scheduleStore.createSchedule(schedule);
            written = true;
            LOGGER.infof("Agent %s booked check-back %s for %s", LogSanitizer.sanitize(booker.agentId()), LogSanitizer.sanitize(id), when);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("booked", true);
            out.put("followupId", id);
            out.put("at", when.toString());
            out.put("target", ownConversation ? "conversation" : "agent");
            out.put("maxCostUsd", limits.effectiveMaxCostPerFireUsd());
            return json(out);
        } catch (Exception e) {
            LOGGER.warnf("Check-back booking failed for agent %s: %s", LogSanitizer.sanitize(booker.agentId()),
                    LogSanitizer.sanitize(e.getMessage()));
            return refused("The check-back could not be booked right now; nothing was scheduled.");
        } finally {
            if (!written) {
                booked.decrementAndGet();
            }
        }
    }

    private String perTurnRefusal() {
        return refused("You have already booked " + limits.effectiveMaxPerTurn() + " check-back(s) this turn.");
    }

    @Tool(name = LIST_TOOL, value = "List the check-backs you have booked that are still waiting to fire.")
    public String listFollowups() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ScheduleConfiguration schedule : pending()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("followupId", schedule.getId());
                row.put("at", schedule.getNextFire() != null ? schedule.getNextFire().toString() : schedule.getOneTimeAt());
                row.put("target", "persistent".equals(schedule.getConversationStrategy()) ? "conversation" : "agent");
                row.put("message", schedule.getMessage());
                rows.add(row);
            }
            return json(Map.of("followups", rows, "maxPending", limits.effectiveMaxPending()));
        } catch (Exception e) {
            return refused("Your check-backs cannot be read right now.");
        }
    }

    @Tool(name = CANCEL_TOOL, value = "Cancel a check-back you booked, by the followupId schedule_followup or list_followups gave you.")
    public String cancelFollowup(@P("The followupId to cancel") String followupId) {
        if (isBlank(followupId)) {
            return refused("A followupId is required.");
        }
        try {
            // Only the caller's own, by looking in the caller's own list: an id that
            // exists but belongs to someone else answers like one that does not.
            ScheduleConfiguration own = pending().stream().filter(s -> followupId.strip().equals(s.getId())).findFirst().orElse(null);
            if (own == null) {
                return refused("No waiting check-back of yours has that id.");
            }
            scheduleStore.deleteSchedule(own.getId());
            return json(Map.of("cancelled", true, "followupId", own.getId()));
        } catch (Exception e) {
            LOGGER.warnf("Check-back cancel failed for agent %s: %s", LogSanitizer.sanitize(booker.agentId()), LogSanitizer.sanitize(e.getMessage()));
            return refused("The check-back could not be cancelled right now.");
        }
    }

    /**
     * This agent's, for this user, enabled and still due. Reads every page of the
     * agent's schedules (up to {@link #MAX_SCAN_PAGES} pages of
     * {@link #SCAN_LIMIT}) before filtering, so a check-back past the first page
     * still counts, lists and cancels.
     */
    List<ScheduleConfiguration> pending() throws Exception {
        List<ScheduleConfiguration> mine = new ArrayList<>();
        for (int page = 0; page < MAX_SCAN_PAGES; page++) {
            List<ScheduleConfiguration> batch = scheduleStore.readSchedulesByAgentId(booker.agentId(), SCAN_LIMIT, page * SCAN_LIMIT, true);
            for (ScheduleConfiguration schedule : batch) {
                Map<String, Object> metadata = schedule.getMetadata();
                boolean followup = metadata != null && METADATA_TYPE_VALUE.equals(metadata.get(METADATA_TYPE_KEY))
                        && booker.agentId().equals(metadata.get(METADATA_BOOKED_BY));
                if (followup && schedule.isEnabled() && schedule.getNextFire() != null && booker.userId().equals(schedule.getCreatedBy())) {
                    mine.add(schedule);
                }
            }
            if (batch.size() < SCAN_LIMIT) {
                break;
            }
        }
        return mine;
    }

    static Instant parse(String at) {
        if (at == null || at.isBlank()) {
            throw new IllegalArgumentException("A time is required, as an ISO-8601 instant with a zone, e.g. 2026-11-10T09:00:00Z.");
        }
        String text = at.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through to an offset form
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "'%s' is not an ISO-8601 time with a zone; use e.g. 2026-11-10T09:00:00Z or 2026-11-10T09:00:00+01:00.",
                    text.length() > 40 ? text.substring(0, 40) : text));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String refused(String message) {
        return json(Map.of("booked", false, "message", message));
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"message\":\"The result could not be rendered.\"}";
        }
    }
}
