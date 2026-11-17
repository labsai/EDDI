/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import ai.labs.eddi.configs.groups.validation.GroupGapsValidation;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The policy half of {@code tiePolicy: HUMAN_DECIDES}: who decides, what they
 * are shown, and the decision their choice becomes. Stateless and free of I/O —
 * the pause itself (commit, resolve, timeout) is {@link GroupTieBreakPauses}.
 * <p>
 * Everything the decider is shown is built from the option strings and the
 * tally's numbers, never from member free text, and their choice must name one
 * option exactly: a tie-break is a selection, not a new answer.
 */
public final class HumanTieBreakEngine {

    private static final Logger LOGGER = Logger.getLogger(HumanTieBreakEngine.class);

    /** The decision method a human tie-break records. */
    public static final String METHOD_HUMAN_TIEBREAK = "vote+human-tiebreak";

    /**
     * How an invalid-choice message starts — the API surfaces that message
     * verbatim, since it names only the group author's option texts.
     */
    public static final String INVALID_CHOICE_PREFIX = "'choice' must name exactly one of the tied options";

    private HumanTieBreakEngine() {
    }

    /**
     * A tied vote that must pause for its decider.
     *
     * @param decider
     *            the principal id that decides
     * @param deciderDisplayName
     *            how the decider is named in the prompt, the transcript and Slack
     * @param options
     *            the options to choose among
     * @param quorumReached
     *            whether the vote tied ({@code true}) or failed quorum
     * @param transcriptFrom
     *            where the VOTE repeat's entries start in the transcript
     * @param timeout
     *            the decider's ISO-8601 window, or {@code null} to wait
     *            indefinitely
     * @param tieBreak
     *            the phase's tie-break settings
     * @param prompt
     *            what the decider is asked
     */
    public record PendingTieBreak(String decider, String deciderDisplayName, List<String> options, boolean quorumReached,
            int transcriptFrom, String timeout, HumanTieBreakConfig tieBreak, String prompt) {
    }

    /**
     * Builds the pending tie-break for a tally that left options unresolved, or
     * {@code null} when no decider can be resolved (hand-edited storage — save time
     * refuses that config), in which case the caller keeps NO_DECISION.
     *
     * @param transcriptFrom
     *            the transcript index the VOTE repeat's entries start at
     */
    public static PendingTieBreak onTie(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                                        VoteConfig voteConfig, VoteTallyEngine.TallyOutcome outcome, int transcriptFrom) {
        HumanTieBreakConfig tieBreak = voteConfig.humanTieBreak() != null ? voteConfig.humanTieBreak() : new HumanTieBreakConfig();
        String decider = GroupGapsValidation.resolveDecider(tieBreak, config);
        if (decider == null) {
            LOGGER.warnf("Group %s: VOTE phase '%s' has tiePolicy HUMAN_DECIDES but no decider can be resolved — "
                    + "recording NO_DECISION", LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phase.name()));
            return null;
        }
        String displayName = displayNameOf(decider, config);
        String timeout = tieBreak.timeout() != null
                ? tieBreak.timeout()
                : config.getHumanMemberConfig() != null ? config.getHumanMemberConfig().turnTimeout() : null;
        String prompt = prompt(phase.name(), outcome.unresolvedOptions(), outcome.quorumReached(), outcome.decision());
        return new PendingTieBreak(decider, displayName, List.copyOf(outcome.unresolvedOptions()), outcome.quorumReached(),
                transcriptFrom, timeout, tieBreak, prompt);
    }

    /**
     * The decider's prompt: the reason, the options and the weighted totals. Built
     * only from option strings and numbers.
     */
    static String prompt(String phaseName, List<String> options, boolean quorumReached, DecisionRecord unresolved) {
        var sb = new StringBuilder();
        sb.append(quorumReached ? "The vote in \"" + phaseName + "\" is tied." : "The vote in \"" + phaseName + "\" did not reach quorum.");
        sb.append(" Choose exactly one option:\n");
        Map<?, ?> totals = totalsOf(unresolved);
        for (String option : options) {
            sb.append("- ").append(option);
            Object total = totals.get(option);
            if (total instanceof Number n) {
                sb.append(" (").append(String.format(Locale.ROOT, "%.2f", n.doubleValue())).append(" weighted votes)");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * The option a choice names: an exact (trimmed) match first, then the same
     * whole-word scan a moderator's tiebreak reply gets. {@code null} unless it
     * names exactly one option.
     */
    public static String matchChoice(String choice, List<String> options) {
        if (choice == null || choice.isBlank() || options == null || options.isEmpty()) {
            return null;
        }
        String trimmed = choice.trim();
        String exact = null;
        for (String option : options) {
            if (option.equals(trimmed)) {
                if (exact != null) {
                    return null;
                }
                exact = option;
            }
        }
        return exact != null ? exact : VoteTallyEngine.resolveChoice(trimmed, options);
    }

    /**
     * Re-reads the VOTE repeat's ballots from the transcript — the pause stores no
     * ballot copies — and re-tallies them against the phase's options, as the
     * group's records count them ({@code records}, taken of {@code gc}): the same
     * options and ballots the live tally counted, so a ballot that does not verify
     * for every reader cannot decide the tie at resume.
     */
    public static VoteTallyEngine.TallyOutcome rebuildOutcome(GroupConversation gc, DiscussionPhase phase, int phaseIdx,
                                                              int transcriptFrom, GroupSigningGuard.View records) {
        VoteConfig voteConfig = phase.voteConfig() != null ? phase.voteConfig() : new VoteConfig();
        List<TranscriptEntry> snapshot = records.stored();
        List<TranscriptEntry> ballots = new ArrayList<>();
        for (int i = Math.max(0, transcriptFrom); i < snapshot.size(); i++) {
            TranscriptEntry entry = snapshot.get(i);
            if (entry != null && entry.type() == TranscriptEntryType.VOTE && entry.phaseIndex() == phaseIdx && records.trusts(entry)) {
                ballots.add(entry);
            }
        }
        // The view keeps every entry in place (a withheld one as a placeholder), so
        // a stored index is an index into it.
        List<TranscriptEntry> visible = records.transcript();
        List<String> options = VoteTallyEngine.resolveOptions(voteConfig, visible.subList(0, Math.min(Math.max(0, transcriptFrom),
                visible.size())));
        int participants = participantsOf(gc.getDecision(), ballots.size());
        return VoteTallyEngine.tally(ballots, participants, options, voteConfig, phase.name());
    }

    /**
     * The decision a human choice becomes: a VOTE record naming the decider, the
     * original tally, and the losing ballots' statements as dissents.
     */
    public static DecisionRecord decisionFor(String choice, String deciderDisplayName, boolean quorumReached,
                                             VoteTallyEngine.TallyOutcome rebuilt, DecisionRecord unresolved, String phaseName,
                                             String rationale) {
        Map<String, Object> tally = unresolved != null && unresolved.tally() != null ? unresolved.tally() : rebuilt.decision().tally();
        return new DecisionRecord(DecisionType.VOTE,
                "\"%s\" chosen by %s (%s).".formatted(choice, deciderDisplayName, quorumReached ? "tie break" : "quorum failure"),
                choice, tally, VoteTallyEngine.losingDissents(rebuilt.ballots(), choice), METHOD_HUMAN_TIEBREAK, phaseName,
                rationale != null && !rationale.isBlank() ? rationale : null);
    }

    /**
     * The NONE record an unanswered tie-break leaves, keeping the tally so readers
     * still see how the vote went.
     */
    public static DecisionRecord noDecision(String deciderDisplayName, String timeout, DecisionRecord unresolved, String phaseName) {
        String outcome = "No decision from " + deciderDisplayName + (timeout != null ? " within " + timeout : "") + ".";
        return new DecisionRecord(DecisionType.NONE, outcome, null, unresolved != null ? unresolved.tally() : null, List.of(),
                VoteTallyEngine.METHOD_VOTE, phaseName, null);
    }

    private static String displayNameOf(String decider, AgentGroupConfiguration config) {
        if (config.getMembers() != null) {
            for (GroupMember member : config.getMembers()) {
                if (member != null && decider.equals(member.agentId()) && member.displayName() != null && !member.displayName().isBlank()) {
                    return member.displayName();
                }
            }
        }
        return decider;
    }

    private static Map<?, ?> totalsOf(DecisionRecord record) {
        if (record != null && record.tally() != null && record.tally().get("totals") instanceof Map<?, ?> totals) {
            return totals;
        }
        return Map.of();
    }

    private static int participantsOf(DecisionRecord record, int fallback) {
        if (record != null && record.tally() != null && record.tally().get("participants") instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }
}
