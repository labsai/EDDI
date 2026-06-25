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

    public static final String TEMPLATE_OPINION_WITH_CONTEXT = """
            The discussion continues.

            Previous responses:
            {#for entry in previousResponses}
            — {entry.speaker}: "{entry.content}"
            {/for}

            As {displayName}, please respond to the others' perspectives.""";

    public static final String TEMPLATE_CRITIQUE = """
            You are reviewing {targetName}'s perspective on:
            "{question}"

            Their response: "{targetResponse}"

            As {displayName}, provide constructive feedback — identify strengths, \
            weaknesses, and suggestions for improvement.""";

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
            Map.entry(PhaseType.VERIFY, TEMPLATE_VERIFY));

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
            case CUSTOM -> List.of();
        };
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
