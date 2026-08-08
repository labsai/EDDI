/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expands {@link DiscussionStyle} presets into concrete {@link DiscussionPhase}
 * lists and provides default Qute templates for each {@link PhaseType}.
 *
 * @author ginccc
 */
public final class DiscussionStylePresets {

    private DiscussionStylePresets() {
    }

    // ------------------------------------------------------------------
    // Default templates (Qute TEXT mode)
    // ------------------------------------------------------------------

    public static final String TEMPLATE_OPINION_INDEPENDENT = """
            A panel of experts is discussing the following question:
            "{question}"

            As {displayName}, please share your professional perspective.""";

    /**
     * The anti-sycophancy directive (I4). Added only to the templates where a
     * member can see what others said — {@link #TEMPLATE_OPINION_WITH_CONTEXT} and
     * {@link #TEMPLATE_CRITIQUE}. {@link #TEMPLATE_OPINION_INDEPENDENT} shows no
     * peers, so there is nobody to agree with, and
     * {@link #TEMPLATE_OPINION_ANONYMOUS} already instructs independent judgment;
     * adding it there would be noise that dilutes the instruction where it counts.
     * <p>
     * The failure it addresses is well documented in multi-agent LLM setups: shown
     * prior responses, models converge on them regardless of merit, which turns a
     * panel into an echo of whoever spoke first. That also silently degrades I2's
     * convergence signal — agreement reached by deference looks identical to
     * agreement reached by persuasion.
     */
    public static final String ANTI_SYCOPHANCY_DIRECTIVE = "State your genuine assessment; do not adjust your position merely to agree with prior speakers.";

    public static final String TEMPLATE_OPINION_WITH_CONTEXT = """
            The discussion continues.

            Previous responses:
            {#for entry in previousResponses}
            — {entry.speaker}: "{entry.content}"
            {/for}

            As {displayName}, please respond to the others' perspectives.
            """ + ANTI_SYCOPHANCY_DIRECTIVE;

    public static final String TEMPLATE_CRITIQUE = """
            You are reviewing {targetName}'s perspective on:
            "{question}"

            Their response: "{targetResponse}"

            As {displayName}, provide constructive feedback — identify strengths, \
            weaknesses, and suggestions for improvement.
            """ + ANTI_SYCOPHANCY_DIRECTIVE;

    public static final String TEMPLATE_REVISION = """
            You previously shared your perspective on:
            "{question}"

            Your original response: "{originalResponse}"

            Feedback received from peers:
            {#for fb in feedbackReceived}
            — {fb.reviewer}: "{fb.content}"
            {/for}

            As {displayName}, please revise your position based on this feedback.""";

    public static final String TEMPLATE_CHALLENGE = """
            A panel has shared their opinions on:
            "{question}"

            Their positions:
            {#for entry in allOpinions}
            — {entry.speaker}: "{entry.content}"
            {/for}

            As the Devil's Advocate, your role is to challenge assumptions, identify \
            weaknesses in reasoning, and argue against the emerging consensus. Be \
            critical, provocative, and thorough.""";

    public static final String TEMPLATE_DEFENSE = """
            Your position on "{question}" has been challenged.

            Your original position: "{originalResponse}"

            Challenge raised:
            {#for ch in challenges}
            — {ch.speaker}: "{ch.content}"
            {/for}

            As {displayName}, defend your position or explain how you would \
            revise it in light of these challenges.""";

    public static final String TEMPLATE_ARGUE = """
            A formal debate is being held on the proposition:
            "{question}"

            {#if opposingArguments}
            The opposing side has argued:
            {#for arg in opposingArguments}
            — {arg.speaker}: "{arg.content}"
            {/for}
            {/if}

            As {displayName} on the {teamSide} side, present your \
            strongest arguments.""";

    public static final String TEMPLATE_REBUTTAL = """
            A formal debate is being held on the proposition:
            "{question}"

            The opposing side has argued:
            {#for arg in opposingArguments}
            — {arg.speaker}: "{arg.content}"
            {/for}

            As {displayName} on the {teamSide} side, counter these \
            arguments point by point.""";

    public static final String TEMPLATE_SYNTHESIS = """
            The panel discussed the following question across {totalPhases} phases:
            "{question}"

            Full transcript:
            {#for entry in transcript}
            [{entry.phaseName}] {entry.speaker}: "{entry.content}"
            {/for}

            Synthesize a balanced conclusion with a clear recommendation.""";

    /**
     * The DEBATE conclusion (I3). Unlike {@link #TEMPLATE_SYNTHESIS}, which asks
     * for a balanced summary, a debate ends in a <em>judgment</em> — and a judgment
     * expressed only as prose cannot be read by anything downstream: a caller
     * wanting the winner has to parse English.
     * <p>
     * The scoring directive is the anti-sycophancy half. An LLM judge shown two
     * sides reliably rewards the more assertive, more fluent, longer argument;
     * saying so explicitly is the documented mitigation, and without it the verdict
     * measures rhetoric rather than the case.
     * <p>
     * <b>{@code reasoning} is deliberately uncapped.</b> It becomes the
     * discussion's {@code synthesizedAnswer} (rendered after the verdict line by
     * {@code DebateVerdictParser}), so every REST/SSE/MCP caller — and every parent
     * group consuming this one as a nested member — reads it in place of the
     * balanced prose {@link #TEMPLATE_SYNTHESIS} used to produce. Asking for "2-3
     * sentences" here would quietly shorten the output of every existing DEBATE
     * config. A config that wants the old prose conclusion back sets the phase's
     * {@code inputTemplate}, which suppresses the verdict path entirely.
     */
    public static final String TEMPLATE_DEBATE_JUDGMENT = """
            You are judging a formal debate on the proposition:
            "{question}"

            Full transcript:
            {#for entry in transcript}
            [{entry.phaseName}] {entry.speaker}: "{entry.content}"
            {/for}

            Score each side on the QUALITY of its argument and the FACTUAL SUPPORT it
            offered. Explicitly do NOT reward assertiveness, confidence, fluency, or
            length — a calmly stated, well-evidenced case beats a forceful, unsupported
            one. A tie is a legitimate verdict; do not manufacture a winner.

            Respond with ONLY this JSON, no other text. Put your full analysis in
            "reasoning" — which arguments carried, what evidence decided it, and where
            the losing side came closest. That text is what the group's readers see as
            the conclusion, so do not abbreviate it:
            {"winner": "PRO" | "CON" | "TIE", "scores": {"PRO": <0-10>, "CON": <0-10>}, "reasoning": "<your full analysis>"}
            """;

    public static final String TEMPLATE_OPINION_ANONYMOUS = """
            A panel of experts is discussing:
            "{question}"

            Anonymous perspectives shared so far:
            {#for entry in previousResponses}
            — "{entry.content}"
            {/for}

            As {displayName}, share your (updated) perspective. Consider the \
            anonymous feedback but form your own independent judgment.""";

    public static final String TEMPLATE_PLAN = """
            You are the project planner for a team of experts.

            GOAL: "{question}"

            TEAM MEMBERS:
            {#for member in members}
            - {member.displayName} (ID: {member.agentId}){#if member.capabilities}, skills: {member.capabilities}{/if}
            {/for}

            Decompose this goal into concrete, actionable tasks. Assign each task to the most \
            suitable team member based on their expertise. Output a JSON array:

            ```json
            [
              {
                "subject": "Short task title",
                "description": "Detailed instructions for the assigned agent",
                "assignedTo": "agent-id or display-name",
                "priority": 0
              }
            ]
            ```

            Rules:
            - Each task must be independently executable
            - Assign tasks based on member expertise
            - Keep tasks focused — one clear deliverable per task
            - Aim for 2-6 tasks for most goals""";

    public static final String TEMPLATE_EXECUTE = """
            You have been assigned the following task as part of a team effort.

            OVERALL GOAL: "{question}"

            YOUR TASK: {taskSubject}
            {taskDescription}

            {#if dependencyResults}
            PREREQUISITE RESULTS:
            {#for dep in dependencyResults}
            - {dep.subject}: {dep.result}
            {/for}
            {/if}

            Complete this task thoroughly. Provide your result as clear, actionable output.""";

    public static final String TEMPLATE_VERIFY = """
            You are reviewing the results of a collaborative task.

            ORIGINAL GOAL: "{question}"

            COMPLETED TASKS:
            {#for task in completedTasks}
            ---
            TASK: {task.subject}
            ASSIGNED TO: {task.assignedDisplayName}
            DESCRIPTION: {task.description}
            RESULT: {task.result}
            ---
            {/for}

            For each task, assess whether the result adequately addresses the task description \
            and contributes to the overall goal. Provide your assessment as JSON:

            ```json
            [
              {"subject": "task title", "passed": true, "feedback": "assessment"}
            ]
            ```""";

    /**
     * The opening offer (I11). The whole reply becomes the proposal's terms —
     * deliberately prose, not JSON: an opening position is authored, not parsed.
     */
    public static final String TEMPLATE_PROPOSAL = """
            A negotiation is underway on:
            "{question}"

            {#if previousResponses}
            Positions and interests stated so far:
            {#for entry in previousResponses}
            — {entry.speaker}: "{entry.content}"
            {/for}
            {/if}

            As {displayName}, state your OPENING PROPOSAL: concrete terms the \
            others could accept. Your entire reply is the proposal.""";

    /**
     * The bargaining contract (I11). The baked-in rules are the anti-sycophancy
     * mechanism: an acceptance must name a specific proposal id, and a concession
     * that does not name what was received in return is not recorded. The current
     * table (open proposals + concession ledger) is appended to this prompt by
     * {@code NegotiationEngine.appendStateIfRelevant}.
     */
    public static final String TEMPLATE_BARGAIN = """
            A negotiation is underway on:
            "{question}"

            As {displayName}, make your bargaining move. Reply with JSON in this exact shape \
            (any field may be null/empty), followed by your free-text reasoning:
            {"accept": "<proposalId>"|null, "proposal": {"terms": "..."}|null, "concessions": [{"gaveUp": "...", "inReturnFor": "..."}]}

            Rules:
            - Do not accept any proposal that fails your stated interests.
            - Every concession must name what you received in return — unreciprocated concessions are not recorded.
            - The ledger below is the record — it will be quoted in the outcome.""";

    /**
     * Arbitration (I11): bargaining ended without unanimous acceptance, so the
     * moderator decides. Only rendered when the phase actually runs — an agreement
     * skips it entirely ({@code skipIf=AGREEMENT_REACHED}).
     */
    public static final String TEMPLATE_ARBITRATION = """
            You are arbitrating a negotiation on:
            "{question}"

            The parties bargained but did NOT reach unanimous agreement. The full \
            transcript is your record:
            {#for entry in transcript}
            [{entry.phaseName}] {entry.speaker}: "{entry.content}"
            {/for}

            As the arbitrator, decide the outcome. Weigh the stated interests, the \
            open proposals and the concession ledger (appended below); state your \
            decision and its reasoning plainly.""";

    // Template lookup by phase type
    private static final Map<PhaseType, String> DEFAULT_TEMPLATES = Map.ofEntries(
            Map.entry(PhaseType.OPINION, TEMPLATE_OPINION_INDEPENDENT),
            Map.entry(PhaseType.CRITIQUE, TEMPLATE_CRITIQUE),
            Map.entry(PhaseType.REVISION, TEMPLATE_REVISION),
            Map.entry(PhaseType.CHALLENGE, TEMPLATE_CHALLENGE),
            Map.entry(PhaseType.DEFENSE, TEMPLATE_DEFENSE),
            Map.entry(PhaseType.ARGUE, TEMPLATE_ARGUE),
            Map.entry(PhaseType.REBUTTAL, TEMPLATE_REBUTTAL),
            Map.entry(PhaseType.SYNTHESIS, TEMPLATE_SYNTHESIS),
            Map.entry(PhaseType.PLAN, TEMPLATE_PLAN),
            Map.entry(PhaseType.EXECUTE, TEMPLATE_EXECUTE),
            Map.entry(PhaseType.VERIFY, TEMPLATE_VERIFY),
            Map.entry(PhaseType.PROPOSAL, TEMPLATE_PROPOSAL),
            Map.entry(PhaseType.BARGAIN, TEMPLATE_BARGAIN));

    /**
     * Returns the default template for a given phase type.
     */
    public static String defaultTemplate(PhaseType type) {
        return DEFAULT_TEMPLATES.getOrDefault(type, TEMPLATE_OPINION_INDEPENDENT);
    }

    // ------------------------------------------------------------------
    // Style → Phases expansion
    // ------------------------------------------------------------------

    /**
     * Expands a {@link DiscussionStyle} preset into a list of concrete phases.
     *
     * @param style
     *            the preset style
     * @param maxRounds
     *            used by ROUND_TABLE and DELPHI for the number of opinion repeats
     * @return ordered list of phases
     */
    public static List<DiscussionPhase> expand(DiscussionStyle style, int maxRounds) {
        if (style == null || style == DiscussionStyle.CUSTOM) {
            return List.of();
        }
        int rounds = Math.max(maxRounds, 1);
        return switch (style) {
            case ROUND_TABLE -> roundTable(rounds);
            case PEER_REVIEW -> peerReview();
            case DEVIL_ADVOCATE -> devilAdvocate();
            case DELPHI -> delphi(rounds);
            case DEBATE -> debate();
            case TASK_FORCE -> taskForce();
            case NEGOTIATION -> negotiation(rounds);
            case CUSTOM -> List.of();
        };
    }

    // --- NEGOTIATION (I11) ---

    /**
     * ① Positions & Interests — PARALLEL and context-free, so parties state genuine
     * interests before anchoring on each other (interests are what enable
     * integrative trades). ② Opening Proposals. ③ Bargaining, repeated
     * {@code rounds} times — the repeat loop exits early on unanimous acceptance. ④
     * Arbitration — skipped entirely when an agreement was reached. ⑤ Synthesis —
     * quotes the ledger.
     */
    private static List<DiscussionPhase> negotiation(int rounds) {
        List<DiscussionPhase> phases = new ArrayList<>();
        phases.add(new DiscussionPhase("Positions & Interests", PhaseType.OPINION, "ALL", TurnOrder.PARALLEL,
                ContextScope.NONE, false, null, 1));
        phases.add(new DiscussionPhase("Opening Proposals", PhaseType.PROPOSAL, "ALL", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, null, 1));
        phases.add(new DiscussionPhase("Bargaining", PhaseType.BARGAIN, "ALL", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, null, rounds));
        phases.add(new DiscussionPhase("Arbitration", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, TEMPLATE_ARBITRATION, 1, false, null, false,
                AgentGroupConfiguration.PhaseSkipCondition.AGREEMENT_REACHED));
        phases.add(new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, null, 1));
        return phases;
    }

    // --- ROUND_TABLE ---

    private static List<DiscussionPhase> roundTable(int rounds) {
        List<DiscussionPhase> phases = new ArrayList<>();

        // First round — independent opinions
        phases.add(new DiscussionPhase("Initial Opinions", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1));

        // Subsequent rounds — with full context
        if (rounds > 1) {
            phases.add(new DiscussionPhase("Discussion", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, rounds - 1));
        }

        // Synthesis
        phases.add(new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1));

        return phases;
    }

    // --- PEER_REVIEW ---

    private static List<DiscussionPhase> peerReview() {
        return List.of(new DiscussionPhase("Initial Opinions", PhaseType.OPINION, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1),
                new DiscussionPhase("Peer Critique", PhaseType.CRITIQUE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, true, null, 1),
                new DiscussionPhase("Revision", PhaseType.REVISION, "ALL", TurnOrder.PARALLEL, ContextScope.OWN_FEEDBACK, false, null, 1),
                new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1));
    }

    // --- DEVIL_ADVOCATE ---

    private static List<DiscussionPhase> devilAdvocate() {
        return List.of(new DiscussionPhase("Initial Opinions", PhaseType.OPINION, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1),
                new DiscussionPhase("Devil's Challenge", PhaseType.CHALLENGE, "ROLE:DEVIL_ADVOCATE", TurnOrder.SEQUENTIAL, ContextScope.FULL, false,
                        null, 1),
                new DiscussionPhase("Defense", PhaseType.DEFENSE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1),
                new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1));
    }

    // --- DELPHI ---

    private static List<DiscussionPhase> delphi(int rounds) {
        List<DiscussionPhase> phases = new ArrayList<>();

        // First round — independent
        phases.add(new DiscussionPhase("Round 1 (Independent)", PhaseType.OPINION, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1));

        // Subsequent rounds — anonymous context
        for (int i = 2; i <= rounds; i++) {
            phases.add(new DiscussionPhase("Round " + i + " (Anonymous)", PhaseType.OPINION, "ALL", TurnOrder.PARALLEL, ContextScope.ANONYMOUS, false,
                    null, 1));
        }

        // Synthesis
        phases.add(new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1));

        return phases;
    }

    // --- DEBATE ---

    private static List<DiscussionPhase> debate() {
        return List.of(
                new DiscussionPhase("Opening Arguments (Pro)", PhaseType.ARGUE, "ROLE:PRO", TurnOrder.SEQUENTIAL, ContextScope.NONE, false, null, 1),
                new DiscussionPhase("Opening Arguments (Con)", PhaseType.ARGUE, "ROLE:CON", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1),
                new DiscussionPhase("Rebuttal (Pro)", PhaseType.REBUTTAL, "ROLE:PRO", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1),
                new DiscussionPhase("Rebuttal (Con)", PhaseType.REBUTTAL, "ROLE:CON", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1),
                new DiscussionPhase("Judgment", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1));
    }

    // --- TASK_FORCE ---

    private static List<DiscussionPhase> taskForce() {
        return List.of(
                new DiscussionPhase("Task Planning", PhaseType.PLAN, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1),
                new DiscussionPhase("Task Execution", PhaseType.EXECUTE, "ALL", TurnOrder.PARALLEL, ContextScope.TASK_ONLY, false, null, 1),
                new DiscussionPhase("Result Verification", PhaseType.VERIFY, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1),
                new DiscussionPhase("Final Synthesis", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1));
    }
}
