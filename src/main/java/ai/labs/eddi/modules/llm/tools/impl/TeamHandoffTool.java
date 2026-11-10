/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.model.HandoffPolicies;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;
import ai.labs.eddi.configs.groups.model.SharedTaskList.Bet;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffRequest;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.inject.Vetoed;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The {@code handoff_to_team} tool: one tool whose {@code team} argument is an
 * ENUM of the aliases the source's own config declared, so a model cannot
 * address a team nobody listed. Everything else (consent, caps, loops, text
 * hygiene) is enforced by {@link TeamHandoffService}; this class only maps one
 * tool call onto one {@link HandoffRequest}.
 * <p>
 * Built by hand rather than reflected: a reflected {@code @Tool} method cannot
 * carry an alias enum that differs per agent, and the reflector would tag it
 * {@code builtin}, which an {@code exempt: ["builtin:*"]} approval config would
 * silently un-gate. The tool is tagged {@code team}.
 * <p>
 * An instance serves ONE turn: the whole tool set is rebuilt per turn, so the
 * per-turn counter below is correctly scoped by construction.
 *
 * @since 6.7.0
 */
@Vetoed
public class TeamHandoffTool {

    public static final String TOOL_NAME = "handoff_to_team";
    /** The approval-pattern source tag: {@code team:handoff_to_team}. */
    public static final String SOURCE_TAG = "team";
    /** Attempts, successful or not, one turn may make: keeps a loop cheap. */
    static final int MAX_ATTEMPTS_PER_TURN = 20;
    static final int MAX_REFERENCE_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Everything the tool needs that is not a model argument.
     *
     * @param sourceKind
     *            derived by the engine
     * @param sourceId
     *            derived by the engine
     * @param targets
     *            the aliases the source may use, already resolved
     * @param maxPerTurn
     *            accepted handoffs one turn may make
     * @param maxDescriptionChars
     *            the policy's description cap
     * @param hop
     *            how many team boundaries the source's work already crossed
     * @param lineage
     *            source keys that work passed through
     * @param runQuota
     *            per-conversation or per-discussion cap
     * @param artifactResolver
     *            maps the model's free-text {@code reference} to a shared-artifact
     *            id, or {@code null}
     * @param onOutcome
     *            called with every attempt's outcome, for the run record and the
     *            SSE queue
     */
    public record Context(SourceKind sourceKind, String sourceId, Integer sourceVersion, String sourceConversationId, String sourceMemberAgentId,
            String userId, List<HandoffTarget> targets, int maxPerTurn, int maxDescriptionChars, int hop, List<String> lineage,
            HandoffRequest.RunQuota runQuota, Function<String, String> artifactResolver, Consumer<Outcome> onOutcome) {
    }

    /** What happened to one attempt. */
    public record Outcome(HandoffTarget target, HandoffResult result, String subject) {
    }

    private final TeamHandoffService service;
    private final Context ctx;
    private final AtomicInteger acceptedThisTurn = new AtomicInteger();
    private final AtomicInteger attemptsThisTurn = new AtomicInteger();

    public TeamHandoffTool(TeamHandoffService service, Context ctx) {
        this.service = service;
        this.ctx = ctx;
    }

    /**
     * The spec the model sees: {@code team} is an enum of exactly the usable
     * aliases.
     */
    public ToolSpecification specification() {
        List<String> aliases = ctx.targets().stream().map(HandoffTarget::alias).toList();
        StringBuilder description = new StringBuilder("Hand a piece of work off to another team. The team's people review it before anyone acts, "
                + "so give the subject and enough context to act on without asking you; never include secrets. "
                + "Hand off once per piece of work: repeating it files nothing new. Teams you may use:");
        for (HandoffTarget target : ctx.targets()) {
            description.append("\n- ").append(target.alias());
            if (target.description() != null && !target.description().isBlank()) {
                description.append(": ").append(target.description());
            }
        }
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addEnumProperty("team", aliases, "Which team to hand off to")
                .addStringProperty("subject", "Short title, under 200 characters")
                .addStringProperty("description", "What needs doing and the context to do it, under " + ctx.maxDescriptionChars() + " characters")
                .addIntegerProperty("priority", "0 = highest priority, 10 = lowest. Omit to use the team's default")
                .addStringProperty("reference", "Optional: the name of the shared artifact or decision this comes from")
                .addStringProperty("hypothesis", "Optional, to file the work as a bet: what you believe it will achieve")
                .addStringProperty("metric", "Optional, with hypothesis: what is measured to judge the bet")
                .addStringProperty("target", "Optional, with hypothesis: the value that counts as success")
                .addStringProperty("reviewDate", "Optional, with hypothesis: ISO date the bet is judged, e.g. 2026-12-01")
                .required("team", "subject", "description").build();
        return ToolSpecification.builder().name(TOOL_NAME).description(description.toString()).parameters(schema).build();
    }

    /** The executor for {@link #specification()}. */
    public ToolExecutor executor() {
        return (ToolExecutionRequest request, Object memoryId) -> execute(request.arguments());
    }

    String execute(String arguments) {
        JsonNode args;
        try {
            args = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception e) {
            return "The handoff arguments were not valid JSON. Nothing was handed off.";
        }
        String alias = text(args, "team");
        HandoffTarget target = HandoffPolicies.findTarget(ctx.targets(), alias);
        if (target == null) {
            return "Unknown team '" + abbreviate(alias, 60) + "'. Choose one of: "
                    + ctx.targets().stream().map(HandoffTarget::alias).collect(Collectors.joining(", ")) + ".";
        }
        if (attemptsThisTurn.incrementAndGet() > MAX_ATTEMPTS_PER_TURN) {
            return "Too many handoff attempts this turn. Stop and tell the user what is left.";
        }
        if (acceptedThisTurn.get() >= ctx.maxPerTurn()) {
            return "You have already handed off " + ctx.maxPerTurn()
                    + " piece(s) of work this turn, which is the limit. Hand off the rest next turn.";
        }
        String subject = text(args, "subject");
        String description = text(args, "description");
        String reference = abbreviate(text(args, "reference"), MAX_REFERENCE_CHARS);
        Integer priority = args.hasNonNull("priority") && args.get("priority").canConvertToInt() ? args.get("priority").asInt() : null;
        if (priority == null) {
            priority = target.defaultPriority();
        }
        String artifactId = reference != null && ctx.artifactResolver() != null ? ctx.artifactResolver().apply(reference) : null;
        Bet bet;
        try {
            bet = Bet.of(text(args, "hypothesis"), text(args, "metric"), text(args, "target"), text(args, "reviewDate"));
        } catch (IllegalArgumentException e) {
            return e.getMessage() + ". Nothing was handed off.";
        }

        HandoffRequest request = new HandoffRequest(ctx.sourceKind(), ctx.sourceId(), ctx.sourceVersion(), ctx.sourceConversationId(),
                ctx.sourceMemberAgentId(), ctx.userId(), target.groupId(), subject, description, priority, Trigger.TOOL, null, artifactId, reference,
                ctx.hop(), ctx.lineage(), null, ctx.maxDescriptionChars(), ctx.runQuota(), bet);
        HandoffResult result = service.file(request);
        if (result.outcome() == HandoffResult.Outcome.FILED || result.outcome() == HandoffResult.Outcome.QUEUED_FOR_REVIEW) {
            acceptedThisTurn.incrementAndGet();
        }
        if (ctx.onOutcome() != null) {
            try {
                ctx.onOutcome().accept(new Outcome(target, result, subject));
            } catch (RuntimeException e) {
                // Bookkeeping must never turn a filed handoff into a tool failure.
            }
        }
        return result.message();
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        String single = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return single.length() <= max ? single : single.substring(0, max);
    }
}
