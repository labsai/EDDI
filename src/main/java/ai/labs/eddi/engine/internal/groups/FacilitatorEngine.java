/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.FacilitatorMove;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The bounded facilitator (I12): at configured checkpoints, briefs a
 * facilitator agent with a <b>compact state summary</b> — never the full
 * transcript — and executes exactly one config-enumerated move from its JSON
 * reply.
 * <p>
 * The design resolves "adaptive orchestration" against deterministic governance
 * (pillar 2) by never letting the model free-form: it <em>selects</em> from
 * {@code allowedMoves}, every selection is validated against the checkpoint's
 * context, capped ({@code maxMovesPerDiscussion},
 * ≤{@value #MAX_EXTENSIONS_PER_PHASE} extensions per phase), and every executed
 * move lands as a peer-hidden {@code FACILITATION} entry + audit event +
 * metric. Anything unparseable, disallowed or invalid-in-context degrades to
 * CONTINUE — recorded as a rejected attempt, because the audit trail must show
 * the model <em>tried</em>.
 * <p>
 * The facilitator call itself is a real LLM turn: it consumes a slot of the
 * turn budget, its dollars land on the cost ledger (I1) like any member turn,
 * and it is skipped entirely once either budget is exhausted. A facilitator
 * failure never fails the discussion — the fallback is always CONTINUE.
 *
 * @author ginccc
 */
public class FacilitatorEngine {

    private static final Logger LOGGER = Logger.getLogger(FacilitatorEngine.class);

    /**
     * The {@code memberConversationIds} key the facilitator's private conversation
     * lives under. Same reasoning as {@code PhaseExecutionEngine}'s judge key: the
     * facilitator may well BE the moderator agent, and its "reply with ONLY this
     * JSON" exchanges must not become that agent's recent history.
     */
    static final String FACILITATOR_CONVERSATION_KEY = "__facilitator";

    /** EXTEND_PHASE ceiling per phase — an extension of an extension is a loop. */
    static final int MAX_EXTENSIONS_PER_PHASE = 2;

    /** CALL_VOTE bounds: a ballot needs a real choice, not an essay per option. */
    static final int MIN_VOTE_OPTIONS = 2;
    static final int MAX_VOTE_OPTIONS = 10;
    static final int MAX_VOTE_OPTION_LENGTH = 500;

    /** ESCALATE_HUMAN question bound — it renders into a pending-input prompt. */
    static final int MAX_ESCALATION_QUESTION_LENGTH = 2_000;

    /** Briefing excerpt bounds — the "never the full transcript" contract. */
    static final int BRIEFING_QUESTION_LENGTH = 300;
    static final int BRIEFING_EXCERPT_LENGTH = 500;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * Same environment rule as {@code RecruitAgentTool}: deployed where member
     * turns run.
     */
    private static final Environment REQUIRED_ENV = Environment.production;

    private final MemberTurnExecutor memberTurnExecutor;
    private final Supplier<IDeploymentStore> deploymentStore;
    private final AuditLedgerService auditLedgerService;
    private final MeterRegistry meterRegistry;

    public FacilitatorEngine(MemberTurnExecutor memberTurnExecutor, Supplier<IDeploymentStore> deploymentStore,
            AuditLedgerService auditLedgerService, MeterRegistry meterRegistry) {
        this.memberTurnExecutor = memberTurnExecutor;
        this.deploymentStore = deploymentStore;
        this.auditLedgerService = auditLedgerService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * What the discussion loop must do with an executed move. RECRUIT is applied
     * inside the engine (it only mutates {@code gc}) and comes back as
     * {@link Kind#NONE}; the other moves change loop control or phase structure,
     * which only the loop itself may do.
     */
    public record FacilitatorAction(Kind kind, DiscussionPhase insertPhase, Escalation escalation) {

        public enum Kind {
            /** Continue unchanged (also every rejected/failed checkpoint). */
            NONE,
            /** Break out of the current phase's remaining repeats. */
            END_PHASE,
            /** The current phase gained one repeat — re-read its bound. */
            EXTEND_PHASE,
            /** Insert {@code insertPhase} directly after the current phase. */
            INSERT_VOTE,
            /** Commit an escalation pause and end the leg. */
            ESCALATE
        }

        /** The pending-input pause ESCALATE_HUMAN asks the loop to commit. */
        public record Escalation(String principalId, String question) {
        }

        static FacilitatorAction none() {
            return new FacilitatorAction(Kind.NONE, null, null);
        }
    }

    /**
     * Everything about WHERE this checkpoint sits that move validation needs.
     *
     * @param repeatCheckpoint
     *            {@code true} when the checkpoint runs inside the repeat loop
     *            (checkAfter=EACH_REPEAT) — the only place END_PHASE/EXTEND_PHASE
     *            have anything to act on
     * @param moreRepeatsScheduled
     *            whether the current phase still has repeats after this one
     * @param phaseEndedBySignal
     *            whether convergence/abstention already ended the phase — a
     *            facilitator must not overrule the deterministic exit
     * @param escalationResumeTargetExists
     *            whether a valid resume point exists for an escalation pause
     *            (mid-phase: the next repeat; boundary: the next phase)
     */
    public record CheckpointContext(int phaseIdx, int repeat, boolean repeatCheckpoint, boolean moreRepeatsScheduled,
            boolean phaseEndedBySignal, boolean escalationResumeTargetExists) {
    }

    /**
     * Runs one facilitator checkpoint. Never throws: every failure path logs and
     * returns {@link FacilitatorAction#none()}.
     */
    public FacilitatorAction checkpoint(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                                        CheckpointContext ctx, ProtocolConfig protocol, String question,
                                        GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns) {
        FacilitatorConfig fc = config.getFacilitator();
        if (fc == null || !fc.enabled() || fc.agentId() == null || fc.agentId().isBlank()) {
            return FacilitatorAction.none();
        }
        // The same two budgets every LLM call in the loop honors (the convergence
        // judge set the precedent): a facilitator consult is an optimization and
        // must never be the marginal call that busts either budget.
        if (maxTurns > 0 && turnCounter != null && turnCounter.get() >= maxTurns) {
            LOGGER.debugf("Facilitator checkpoint for %s skipped — turn budget exhausted", gc.getId());
            return FacilitatorAction.none();
        }
        if (GroupCostLedger.wouldExceedCeiling(gc, protocol)) {
            LOGGER.debugf("Facilitator checkpoint for %s skipped — cost ceiling reached", gc.getId());
            return FacilitatorAction.none();
        }
        if (turnCounter != null) {
            turnCounter.incrementAndGet();
        }

        String briefing = buildBriefing(gc, config, fc, phase, ctx, protocol, question);
        String reply;
        try {
            var facilitator = new GroupMember(fc.agentId(), "Facilitator", 0, "FACILITATOR", MemberType.AGENT);
            TranscriptEntry entry = memberTurnExecutor.executeAgentTurn(facilitator, gc, briefing, protocol,
                    ctx.phaseIdx(), phase, null, listener, null, FACILITATOR_CONVERSATION_KEY);
            reply = entry != null ? entry.content() : null;
        } catch (Exception e) {
            // Facilitator failure → CONTINUE, per the plan. No transcript entry:
            // the model never replied, so there is no attempt to audit.
            LOGGER.warnf("Facilitator call failed for group %s at phase %d — continuing without intervention: %s",
                    LogSanitizer.sanitize(gc.getId()), ctx.phaseIdx(), LogSanitizer.sanitize(e.getMessage()));
            return FacilitatorAction.none();
        }

        // A null/blank reply is a FAILED call (member-unavailable SKIP, empty
        // completion), not a model attempting something — same no-entry rule as the
        // exception path above. Rejection entries are reserved for replies that
        // exist but violate the contract.
        if (reply == null || reply.isBlank()) {
            LOGGER.warnf("Facilitator returned no reply for group %s at phase %d — continuing without intervention",
                    LogSanitizer.sanitize(gc.getId()), ctx.phaseIdx());
            return FacilitatorAction.none();
        }

        ParsedMove parsed = parseMove(reply);
        if (parsed == null) {
            recordRejection(gc, fc, phase, ctx, "(unparseable)", null,
                    "reply did not contain the required {\"move\", \"args\", \"reason\"} JSON");
            return FacilitatorAction.none();
        }
        if (parsed.move() == null) {
            recordRejection(gc, fc, phase, ctx, parsed.rawMove(), parsed.reason(),
                    "'" + parsed.rawMove() + "' is not a recognized move");
            return FacilitatorAction.none();
        }
        FacilitatorMove move = parsed.move();
        meterRegistry.counter("eddi_group_facilitator_moves_total",
                "move", move.name(), "outcome", "proposed").increment();

        if (!fc.allowedMoves().contains(move)) {
            recordRejection(gc, fc, phase, ctx, move.name(), parsed.reason(),
                    "move is not in this group's allowedMoves");
            return FacilitatorAction.none();
        }
        if (move == FacilitatorMove.CONTINUE) {
            // The ambient no-op: no transcript entry, no move budget spent. The
            // proposed-counter above is its record.
            return FacilitatorAction.none();
        }
        if (gc.getFacilitatorMoveCount() >= fc.maxMovesPerDiscussion()) {
            recordRejection(gc, fc, phase, ctx, move.name(), parsed.reason(),
                    "move budget exhausted (" + fc.maxMovesPerDiscussion() + " non-CONTINUE moves)");
            return FacilitatorAction.none();
        }

        return switch (move) {
            case END_PHASE -> executeEndPhase(gc, fc, phase, ctx, parsed);
            case EXTEND_PHASE -> executeExtendPhase(gc, fc, phase, ctx, parsed);
            case CALL_VOTE -> executeCallVote(gc, fc, phase, ctx, parsed);
            case RECRUIT -> executeRecruit(gc, config, fc, phase, ctx, parsed);
            case ESCALATE_HUMAN -> executeEscalate(gc, fc, phase, ctx, parsed);
            case CONTINUE -> FacilitatorAction.none(); // unreachable — handled above
        };
    }

    // =================================================================
    // Move execution
    // =================================================================

    private FacilitatorAction executeEndPhase(GroupConversation gc, FacilitatorConfig fc, DiscussionPhase phase,
                                              CheckpointContext ctx, ParsedMove parsed) {
        if (!ctx.repeatCheckpoint() || !ctx.moreRepeatsScheduled() || ctx.phaseEndedBySignal()) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "END_PHASE only applies mid-phase, before the phase has already ended");
            return FacilitatorAction.none();
        }
        recordExecution(gc, fc, phase, ctx, parsed, "ended phase '" + phase.name() + "' early", null);
        return new FacilitatorAction(FacilitatorAction.Kind.END_PHASE, null, null);
    }

    private FacilitatorAction executeExtendPhase(GroupConversation gc, FacilitatorConfig fc, DiscussionPhase phase,
                                                 CheckpointContext ctx, ParsedMove parsed) {
        if (!ctx.repeatCheckpoint() || ctx.phaseEndedBySignal()) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "EXTEND_PHASE only applies mid-phase, and never after a convergence exit");
            return FacilitatorAction.none();
        }
        String key = String.valueOf(ctx.phaseIdx());
        int extensions = gc.getFacilitatorExtensions().getOrDefault(key, 0);
        if (extensions >= MAX_EXTENSIONS_PER_PHASE) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "phase already extended " + MAX_EXTENSIONS_PER_PHASE + " times");
            return FacilitatorAction.none();
        }
        gc.getFacilitatorExtensions().put(key, extensions + 1);
        recordExecution(gc, fc, phase, ctx, parsed, "extended phase '" + phase.name() + "' by one repeat", null);
        return new FacilitatorAction(FacilitatorAction.Kind.EXTEND_PHASE, null, null);
    }

    private FacilitatorAction executeCallVote(GroupConversation gc, FacilitatorConfig fc, DiscussionPhase phase,
                                              CheckpointContext ctx, ParsedMove parsed) {
        List<String> options = new ArrayList<>();
        JsonNode optionsNode = parsed.args().path("options");
        if (optionsNode.isArray()) {
            for (JsonNode option : optionsNode) {
                String text = option.isTextual() ? option.asText().trim() : "";
                if (!text.isEmpty() && !options.contains(text)) {
                    options.add(text.length() > MAX_VOTE_OPTION_LENGTH ? text.substring(0, MAX_VOTE_OPTION_LENGTH) : text);
                }
            }
        }
        if (options.size() < MIN_VOTE_OPTIONS || options.size() > MAX_VOTE_OPTIONS) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "CALL_VOTE needs args.options with " + MIN_VOTE_OPTIONS + ".." + MAX_VOTE_OPTIONS
                            + " distinct non-empty strings (got " + options.size() + ")");
            return FacilitatorAction.none();
        }
        // The synthetic phase is built to I14's enforced shape (PARALLEL + NONE) by
        // construction — a facilitator vote gets the same structural independence a
        // configured one has. One repeat, no tie policy drama: an unresolved
        // facilitator vote records an honest NONE and the discussion continues.
        var votePhase = new DiscussionPhase("Facilitator Vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL,
                ContextScope.NONE, false, null, 1, false, null, false,
                new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, options, VoteConfig.DEFAULT_QUORUM,
                        Map.of(), false, TiePolicy.NO_DECISION));
        recordExecution(gc, fc, phase, ctx, parsed,
                "called a vote on: " + String.join(" | ", options), null);
        return new FacilitatorAction(FacilitatorAction.Kind.INSERT_VOTE, votePhase, null);
    }

    /**
     * The I7 path, mirrored from {@code RecruitAgentTool} (which is a per-turn tool
     * over the live registry and cannot be constructed here): same already-member
     * check, same {@code maxRecruitedAgentsPerDiscussion} cap, same
     * deployed-and-ready requirement, same synchronized commit over both lists.
     */
    private FacilitatorAction executeRecruit(GroupConversation gc, AgentGroupConfiguration config, FacilitatorConfig fc,
                                             DiscussionPhase phase, CheckpointContext ctx, ParsedMove parsed) {
        String agentId = parsed.args().path("agentId").asText("").trim();
        if (agentId.isEmpty()) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "RECRUIT needs args.agentId");
            return FacilitatorAction.none();
        }
        String problem = recruitProblem(gc, config, agentId);
        if (problem != null) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(), problem);
            return FacilitatorAction.none();
        }
        var recruit = new GroupMember(agentId, agentId, Integer.MAX_VALUE, null, MemberType.AGENT);
        synchronized (gc.getRecruitedAgentIds()) {
            if (recruitProblem(gc, config, agentId) != null) {
                recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                        "agent was recruited concurrently or the limit was reached");
                return FacilitatorAction.none();
            }
            gc.addDynamicMember(recruit);
            gc.getRecruitedAgentIds().add(agentId);
        }
        gc.addMemberDisplayName(agentId, agentId);
        recordExecution(gc, fc, phase, ctx, parsed, "recruited agent '" + agentId + "'", agentId);
        return FacilitatorAction.none(); // applied here — nothing for the loop to do
    }

    private FacilitatorAction executeEscalate(GroupConversation gc, FacilitatorConfig fc, DiscussionPhase phase,
                                              CheckpointContext ctx, ParsedMove parsed) {
        if (fc.escalateTo() == null || fc.escalateTo().isBlank()) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "no escalateTo principal configured");
            return FacilitatorAction.none();
        }
        if (!ctx.escalationResumeTargetExists()) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "nothing left to resume into after this checkpoint — escalation would strand the discussion");
            return FacilitatorAction.none();
        }
        String escalationQuestion = parsed.args().path("question").asText("").trim();
        if (escalationQuestion.isEmpty()) {
            escalationQuestion = parsed.reason() != null ? parsed.reason().trim() : "";
        }
        if (escalationQuestion.isEmpty()) {
            recordRejection(gc, fc, phase, ctx, parsed.move().name(), parsed.reason(),
                    "ESCALATE_HUMAN needs args.question (or a reason to use as one)");
            return FacilitatorAction.none();
        }
        if (escalationQuestion.length() > MAX_ESCALATION_QUESTION_LENGTH) {
            escalationQuestion = escalationQuestion.substring(0, MAX_ESCALATION_QUESTION_LENGTH);
        }
        recordExecution(gc, fc, phase, ctx, parsed,
                "escalated to '" + fc.escalateTo() + "': " + escalationQuestion, null);
        return new FacilitatorAction(FacilitatorAction.Kind.ESCALATE, null,
                new FacilitatorAction.Escalation(fc.escalateTo(), escalationQuestion));
    }

    /**
     * The recruit validation matrix, as one human-readable problem or {@code null}.
     */
    private String recruitProblem(GroupConversation gc, AgentGroupConfiguration config, String agentId) {
        if (config.getMembers() != null
                && config.getMembers().stream().anyMatch(m -> agentId.equals(m.agentId()))) {
            return "agent '" + agentId + "' is already a configured member";
        }
        if (gc.getRecruitedAgentIds().contains(agentId)) {
            return "agent '" + agentId + "' was already recruited";
        }
        List<GroupMember> dynamic = gc.getDynamicMembers();
        synchronized (dynamic) {
            if (dynamic.stream().anyMatch(m -> agentId.equals(m.agentId()))) {
                return "agent '" + agentId + "' is already a dynamic member";
            }
        }
        if (gc.getMemberConversationIds() != null && gc.getMemberConversationIds().containsKey(agentId)) {
            return "agent '" + agentId + "' is already participating";
        }
        int cap = config.getDynamicAgents() != null
                ? config.getDynamicAgents().getMaxRecruitedAgentsPerDiscussion()
                : new AgentGroupConfiguration.DynamicAgentConfig().getMaxRecruitedAgentsPerDiscussion();
        if (gc.getRecruitedAgentIds().size() >= cap) {
            return "recruit limit of " + cap + " reached";
        }
        if (!isDeployedAndReady(agentId)) {
            return "agent '" + agentId + "' is not deployed and ready";
        }
        return null;
    }

    /** Same rule as {@code RecruitAgentTool}: unverifiable means not ready. */
    private boolean isDeployedAndReady(String agentId) {
        IDeploymentStore store = deploymentStore != null ? deploymentStore.get() : null;
        if (store == null) {
            return false;
        }
        try {
            List<DeploymentInfo> deployments = store.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed);
            return deployments != null && deployments.stream()
                    .anyMatch(d -> d != null && agentId.equals(d.getAgentId())
                            && (d.getEnvironment() == null || d.getEnvironment() == REQUIRED_ENV));
        } catch (Exception e) {
            LOGGER.warnf("Could not verify deployment of '%s' — refusing facilitator recruitment: %s",
                    LogSanitizer.sanitize(agentId), LogSanitizer.sanitize(e.getMessage()));
            return false;
        }
    }

    // =================================================================
    // Recording — transcript, audit, metrics
    // =================================================================

    private void recordExecution(GroupConversation gc, FacilitatorConfig fc, DiscussionPhase phase,
                                 CheckpointContext ctx, ParsedMove parsed, String summary, String targetAgentId) {
        gc.setFacilitatorMoveCount(gc.getFacilitatorMoveCount() + 1);
        String content = "Facilitator " + summary
                + (parsed.reason() != null && !parsed.reason().isBlank() ? " — " + parsed.reason().trim() : "");
        gc.getTranscript().add(new TranscriptEntry(fc.agentId(), "Facilitator", content, ctx.phaseIdx(), phase.name(),
                TranscriptEntryType.FACILITATION, Instant.now(), null, targetAgentId));
        meterRegistry.counter("eddi_group_facilitator_moves_total",
                "move", parsed.move().name(), "outcome", "executed").increment();
        auditMove(gc, parsed, ctx, "EXECUTED", null);
        LOGGER.infof("Facilitator move %s executed for group %s at phase %d (%d of %d moves used)",
                parsed.move().name(), LogSanitizer.sanitize(gc.getId()), ctx.phaseIdx(),
                gc.getFacilitatorMoveCount(), fc.maxMovesPerDiscussion());
    }

    /**
     * A rejected attempt: CONTINUE + WARN + a FACILITATION entry that shows the
     * model tried. Never consumes the move budget.
     */
    private void recordRejection(GroupConversation gc, FacilitatorConfig fc, DiscussionPhase phase,
                                 CheckpointContext ctx, String attemptedMove, String reason, String why) {
        String content = "Facilitator attempted " + attemptedMove + " — rejected: " + why
                + (reason != null && !reason.isBlank() ? ". Facilitator's reason: " + reason.trim() : "");
        gc.getTranscript().add(new TranscriptEntry(fc.agentId(), "Facilitator", content, ctx.phaseIdx(), phase.name(),
                TranscriptEntryType.FACILITATION, Instant.now(), null, null));
        meterRegistry.counter("eddi_group_facilitator_moves_total",
                "move", attemptedMove, "outcome", "rejected").increment();
        LOGGER.warnf("Facilitator move %s rejected for group %s at phase %d: %s",
                LogSanitizer.sanitize(attemptedMove), LogSanitizer.sanitize(gc.getId()), ctx.phaseIdx(),
                LogSanitizer.sanitize(why));
    }

    private void auditMove(GroupConversation gc, ParsedMove parsed, CheckpointContext ctx, String outcome, String why) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("move", parsed.move() != null ? parsed.move().name() : parsed.rawMove());
            detail.put("outcome", outcome);
            detail.put("phaseIdx", ctx.phaseIdx());
            detail.put("repeat", ctx.repeat());
            if (parsed.reason() != null) {
                detail.put("reason", parsed.reason());
            }
            if (why != null) {
                detail.put("rejectionReason", why);
            }
            auditLedgerService.submit(new AuditEntry(
                    UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(),
                    null, -1, "group.facilitator", "group", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit facilitator audit entry for group conversation %s: %s",
                    LogSanitizer.sanitize(gc.getId()), e.getMessage());
        }
    }

    // =================================================================
    // Reply parsing — the shared three-tier discipline
    // =================================================================

    /**
     * @param move
     *            {@code null} when {@code rawMove} named no known move
     */
    record ParsedMove(FacilitatorMove move, String rawMove, JsonNode args, String reason) {
    }

    /**
     * Strict JSON → embedded JSON → give up ({@code null}). Mirrors
     * {@link VoteTallyEngine}/{@code DebateVerdictParser}: {@code
     * FAIL_ON_TRAILING_TOKENS} keeps a two-object reply ambiguous, and an
     * unreadable reply has no state effect (it becomes a recorded rejection).
     */
    static ParsedMove parseMove(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        JsonNode node = readJson(reply);
        if (node == null) {
            node = readJson(embeddedJson(reply));
        }
        if (node == null || !node.isObject() || !node.path("move").isTextual()) {
            return null;
        }
        String rawMove = node.path("move").asText().trim();
        FacilitatorMove move = null;
        try {
            move = FacilitatorMove.valueOf(rawMove.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // unknown move — reported by the caller as a rejected attempt
        }
        JsonNode args = node.path("args");
        if (!args.isObject()) {
            args = MAPPER.createObjectNode();
        }
        String reason = node.path("reason").isTextual() ? node.path("reason").asText() : null;
        return new ParsedMove(move, rawMove, args, reason);
    }

    private static JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String embeddedJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : null;
    }

    // =================================================================
    // Briefing — compact by construction
    // =================================================================

    /**
     * The facilitator's entire view of the discussion: derived counts, budget
     * arithmetic and two capped excerpts. Deliberately NOT the transcript — a
     * facilitator that reads everything becomes an orchestrator, and its context
     * cost would grow with the discussion instead of staying flat.
     */
    String buildBriefing(GroupConversation gc, AgentGroupConfiguration config, FacilitatorConfig fc,
                         DiscussionPhase phase, CheckpointContext ctx, ProtocolConfig protocol, String question) {
        var sb = new StringBuilder(1_024);
        sb.append("You are the neutral facilitator of a multi-agent group discussion. ")
                .append("Review the state summary and choose exactly ONE move.\n")
                .append("Respond with ONLY this JSON: {\"move\": \"<MOVE>\", \"args\": {…}, \"reason\": \"<one sentence>\"}\n\n")
                .append("Moves you may choose:\n");
        for (FacilitatorMove move : fc.allowedMoves()) {
            sb.append("- ").append(moveContract(move)).append('\n');
        }

        sb.append("\nState:\n");
        if (question != null && !question.isBlank()) {
            sb.append("- Question: ").append(truncate(question, BRIEFING_QUESTION_LENGTH)).append('\n');
        }
        int repeats = Math.max(phase.repeats(), 1);
        sb.append("- Current phase: '").append(phase.name()).append("' (").append(phase.type())
                .append("), repeat ").append(ctx.repeat() + 1).append(" of ").append(repeats).append('\n');
        Double ceiling = protocol != null ? protocol.maxCostPerDiscussion() : null;
        sb.append("- Cost so far: $").append(String.format(Locale.ROOT, "%.4f", gc.getTotalCost()))
                .append(ceiling != null ? " of $" + String.format(Locale.ROOT, "%.2f", ceiling) + " ceiling" : " (no ceiling)")
                .append('\n');
        sb.append("- Non-CONTINUE moves you have left: ")
                .append(Math.max(0, fc.maxMovesPerDiscussion() - gc.getFacilitatorMoveCount())).append('\n');

        if (config.getMembers() != null && !config.getMembers().isEmpty()) {
            sb.append("- Roster: ").append(config.getMembers().stream()
                    .map(m -> m.agentId() + (m.role() != null ? " (" + m.role() + ")" : ""))
                    .collect(Collectors.joining(", "))).append('\n');
        }
        List<GroupMember> dynamic = gc.getDynamicMembers();
        synchronized (dynamic) {
            if (!dynamic.isEmpty()) {
                sb.append("- Recruited: ").append(dynamic.stream().map(GroupMember::agentId)
                        .collect(Collectors.joining(", "))).append('\n');
            }
        }

        Map<TranscriptEntryType, Long> phaseCounts;
        String latestConvergence = null;
        String latestExcerpt = null;
        synchronized (gc.getTranscript()) {
            phaseCounts = gc.getTranscript().stream()
                    .filter(e -> e.phaseIndex() == ctx.phaseIdx())
                    .collect(Collectors.groupingBy(TranscriptEntry::type, LinkedHashMap::new, Collectors.counting()));
            for (int i = gc.getTranscript().size() - 1; i >= 0; i--) {
                TranscriptEntry entry = gc.getTranscript().get(i);
                if (latestConvergence == null && entry.type() == TranscriptEntryType.CONVERGENCE
                        && entry.content() != null) {
                    latestConvergence = truncate(entry.content(), 200);
                }
                if (latestExcerpt == null && entry.content() != null
                        && (entry.type() == TranscriptEntryType.SYNTHESIS
                                || entry.type() == TranscriptEntryType.OPINION
                                || entry.type() == TranscriptEntryType.REVISION)) {
                    latestExcerpt = truncate(entry.content(), BRIEFING_EXCERPT_LENGTH);
                }
                if (latestConvergence != null && latestExcerpt != null) {
                    break;
                }
            }
        }
        if (!phaseCounts.isEmpty()) {
            sb.append("- Entries this phase: ").append(phaseCounts.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "))).append('\n');
        }
        if (latestConvergence != null) {
            sb.append("- Latest convergence check: ").append(latestConvergence).append('\n');
        }
        var taskList = gc.getTaskList();
        if (taskList != null && !taskList.getTasks().isEmpty()) {
            var byStatus = taskList.getTasks().stream()
                    .collect(Collectors.groupingBy(t -> t.status().name(), LinkedHashMap::new, Collectors.counting()));
            sb.append("- Tasks: ").append(byStatus.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "))).append('\n');
        }
        if (latestExcerpt != null) {
            sb.append("- Latest contribution excerpt: ").append(latestExcerpt).append('\n');
        }
        sb.append("\nWhen in doubt, choose CONTINUE.");
        return sb.toString();
    }

    private static String moveContract(FacilitatorMove move) {
        return switch (move) {
            case CONTINUE -> "CONTINUE — no intervention (no args)";
            case END_PHASE -> "END_PHASE — skip this phase's remaining repeats (no args)";
            case EXTEND_PHASE -> "EXTEND_PHASE — give this phase one more repeat (no args)";
            case CALL_VOTE -> "CALL_VOTE — insert a vote next; args: {\"options\": [\"…\", \"…\"]} with "
                    + MIN_VOTE_OPTIONS + "-" + MAX_VOTE_OPTIONS + " short, distinct options";
            case RECRUIT -> "RECRUIT — bring a deployed agent into the roster; args: {\"agentId\": \"…\"}";
            case ESCALATE_HUMAN -> "ESCALATE_HUMAN — pause and ask the human overseer; args: {\"question\": \"…\"}";
        };
    }

    private static String truncate(String text, int max) {
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
