/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.Dissent;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancellation;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancelledException;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the debate-style phase turn orders (sequential, parallel, and
 * peer-targeted) over a phase's resolved speaker list. Extracted from
 * {@code GroupConversationService} (Wave R, R1 step 5) as a pure move — no
 * behavior change. TASK_FORCE's PLAN/EXECUTE/VERIFY phase routing is a separate
 * cluster, extracted into {@link TaskForceEngine} in R1 step 6.
 * <p>
 * Shares the facade's single virtual-thread {@link ExecutorService} (passed in,
 * not owned here — {@code GroupConversationService} keeps the
 * {@code @PreDestroy} shutdown hook) since {@code TaskForceEngine}'s execution
 * waves submit to the same executor.
 *
 * @author ginccc
 */
public class PhaseExecutionEngine {

    private static final Logger LOGGER = Logger.getLogger(PhaseExecutionEngine.class);

    private final MemberTurnExecutor memberTurnExecutor;
    private final GroupContextBuilder contextBuilder;
    private final ExecutorService executorService;
    private final CallerIdentityContext callerIdentityContext;

    /**
     * The {@code memberConversationIds} key the convergence judge's own private
     * conversation lives under (I2). Deliberately not an agent id, so it can never
     * collide with a real member's key — a member agent literally named this would
     * still be keyed by its own id in every other code path.
     */
    static final String JUDGE_CONVERSATION_KEY = "__convergence_judge";

    /**
     * Prefix for each dissenter's own dissent-round conversation key (I4). Same
     * reasoning as {@link #JUDGE_CONVERSATION_KEY}: a "critique the synthesis"
     * exchange in the member's own conversation becomes recent context a later
     * round reads back.
     */
    static final String DISSENT_CONVERSATION_KEY_PREFIX = "__dissent__";

    public PhaseExecutionEngine(MemberTurnExecutor memberTurnExecutor, GroupContextBuilder contextBuilder,
            ExecutorService executorService, CallerIdentityContext callerIdentityContext) {
        this.memberTurnExecutor = memberTurnExecutor;
        this.contextBuilder = contextBuilder;
        this.executorService = executorService;
        this.callerIdentityContext = callerIdentityContext;
    }

    /**
     * Control-flow signal (I6): the phase reached a HUMAN member's turn and the
     * discussion must pause for their input. Thrown by the sequential loop and the
     * parallel human tail, caught ONLY by {@code executeDiscussion}'s phase
     * dispatch, which commits the {@code AWAITING_HUMAN_INPUT} pause and returns. A
     * RuntimeException on purpose — it must fly through method signatures that
     * declare {@code GroupDiscussionException} WITHOUT being caught by the generic
     * failure handling (the same reasoning as
     * {@code GroupConversationService.MemberTurnCancelledException}).
     */
    public static final class HumanTurnRequired extends RuntimeException {
        private final transient GroupMember member;
        private final int speakerIdx;
        private final String renderedPrompt;
        private final boolean parallel;

        /**
         * @param member
         *            the HUMAN member whose turn is up
         * @param speakerIdx
         *            the member's index — into the phase's resolved speaker list for
         *            sequential turns, into the phase's human-only sublist for parallel
         *            ones ({@code parallel} distinguishes the two)
         * @param renderedPrompt
         *            the phase input rendered for this member, exactly as an agent
         *            would have received it
         * @param parallel
         *            whether this turn belongs to a PARALLEL phase's human tail
         */
        public HumanTurnRequired(GroupMember member, int speakerIdx, String renderedPrompt, boolean parallel) {
            super("Human member turn required: " + member.agentId(), null, false, false);
            this.member = member;
            this.speakerIdx = speakerIdx;
            this.renderedPrompt = renderedPrompt;
            this.parallel = parallel;
        }

        public GroupMember member() {
            return member;
        }

        public int speakerIdx() {
            return speakerIdx;
        }

        public String renderedPrompt() {
            return renderedPrompt;
        }

        public boolean parallel() {
            return parallel;
        }
    }

    public void executeSequentialPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                       ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                       AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns, 0);
    }

    /**
     * Runs the convergence check for one just-finished phase repeat (I2), and
     * returns what the discussion loop should do next.
     * <p>
     * Ordering is deliberate: the deterministic all-abstained check runs first and
     * short-circuits, so a unanimously silent round never pays for a judge call to
     * tell it what it already knows. The judge runs only when that fails,
     * convergence is enabled, {@code minRepeats} has elapsed, and there is a
     * previous round to compare against.
     * <p>
     * A judge failure is never fatal to the discussion: {@code executeAgentTurn}
     * already converts an agent failure into a SKIPPED/ERROR entry rather than
     * throwing under the default SKIP policy, and the surrounding catch turns
     * anything that does escape into "not converged". A convergence check is an
     * optimization; it must never be the reason a discussion dies.
     *
     * @param repeatEntries
     *            the transcript entries this repeat produced
     * @param previousRepeatEntries
     *            the previous repeat's entries, or {@code null} on the first repeat
     *            — the judge needs a baseline and is skipped without one
     */
    public PhaseOutcome checkConvergence(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase, ProtocolConfig protocol,
                                         int phaseIdx, int repeat, List<GroupMember> speakers, List<TranscriptEntry> repeatEntries,
                                         List<TranscriptEntry> previousRepeatEntries, GroupDiscussionEventListener listener,
                                         AtomicInteger turnCounter, int maxTurns) {

        ConvergenceConfig convergence = phase.convergence();

        // The free mechanism, and the only one that runs when convergence is not
        // configured at all: I4's unanimous PASS is unambiguous evidence on its own
        // terms, so it needs neither an opt-in nor a minRepeats baseline.
        //
        // The denominator is TURNS SCHEDULED, not speakers: a peer-targeted phase
        // runs N×(N-1) turns for N speakers, so comparing against speakers.size()
        // both fired falsely (3 of 6 entries abstaining in a 3-member critique
        // round read as "all 3 abstained" and ended a phase that produced real
        // critiques) and failed to fire when every one of the 6 genuinely did.
        // config.getMembers(), because that is what executePeerTargetedPhase's own
        // target loop iterates. Passing the recruit-inclusive roster here made the
        // denominator disagree with the loop again — 4 speakers over a 3-member
        // target roster run 9 turns, and this computed 12, putting I4's unanimous-
        // abstention exit permanently out of reach.
        int expectedTurns = expectedTurnsFor(phase, speakers, config != null ? config.getMembers() : null);
        if (ConvergenceDetector.allParticipantsAbstained(repeatEntries, expectedTurns)) {
            String reason = "All %d participants abstained — nothing further to add".formatted(expectedTurns);
            recordConvergence(gc, phase, phaseIdx, repeat, -1, true, reason, listener);
            return PhaseOutcome.endRepeats(reason);
        }

        if (convergence == null || !convergence.enabled()) {
            return PhaseOutcome.cont();
        }
        // repeat is 0-based: with the default minRepeats=2 the first check happens
        // after the SECOND repeat (repeat index 1), which is the first one that has
        // a predecessor to differ from.
        if (repeat + 1 < convergence.minRepeats() || previousRepeatEntries == null || previousRepeatEntries.isEmpty()) {
            return PhaseOutcome.cont();
        }
        // An empty current round means the repeat produced nothing — the turn budget
        // ran out mid-phase, or every member errored. There is no position to compare,
        // and a judge handed "(no contributions)" can read silence as agreement and
        // write a convergence_reached record for a phase that actually ran out of
        // budget. Skipping is both cheaper and more honest.
        if (repeatEntries == null || repeatEntries.isEmpty()) {
            return PhaseOutcome.cont();
        }
        // The judge is a real LLM call, so it is subject to the same two budgets every
        // member turn is. maxTurns: without this a repeats=10 phase adds ten uncapped
        // calls behind the cap's back. Cost ceiling: the last speaker of this repeat
        // may have just crossed it, and I1's per-speaker gate cannot have seen that.
        if (maxTurns > 0 && turnCounter != null && turnCounter.get() >= maxTurns) {
            return PhaseOutcome.cont();
        }
        if (GroupCostLedger.wouldExceedCeiling(gc, protocol)) {
            return PhaseOutcome.cont();
        }
        if (turnCounter != null) {
            turnCounter.incrementAndGet();
        }

        var verdict = runJudge(gc, config, phase, protocol, phaseIdx, convergence, repeatEntries, previousRepeatEntries, listener);
        recordConvergence(gc, phase, phaseIdx, repeat, verdict.agreementScore(), verdict.converged(), verdict.summary(), listener);
        return verdict.converged() ? PhaseOutcome.endRepeats(verdict.summary()) : PhaseOutcome.cont();
    }

    /**
     * The minority report (I4b): after a SYNTHESIS phase, every participant who did
     * NOT write the synthesis gets one short turn to record where they still
     * materially disagree.
     * <p>
     * Runs against the members rather than the synthesizer on purpose. Asking the
     * synthesizer to enumerate the objections to its own synthesis is exactly the
     * failure a minority report exists to prevent — the author is the one party
     * structurally unable to report what their summary left out.
     * <p>
     * Non-PASS replies become public {@code DISSENT} entries (unlike
     * {@code ABSTAINED}, peers and readers are meant to see these) and populate
     * {@code DecisionRecord.dissents}. Bounded by the same two budgets as any other
     * turn: it stops at {@code maxTurns} and declines to start once I1's cost
     * ceiling is already blown.
     *
     * @return the number of dissents recorded
     */
    public int runDissentRound(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase, ProtocolConfig protocol,
                               int phaseIdx, List<GroupMember> synthesizers, GroupDiscussionEventListener listener,
                               AtomicInteger turnCounter, int maxTurns) {

        String synthesis = latestSynthesis(gc);
        Set<String> synthesizerIds = synthesizers == null
                ? Set.of()
                : synthesizers.stream().map(GroupMember::agentId).filter(Objects::nonNull).collect(Collectors.toSet());

        // The EFFECTIVE roster, recruits included. Reading config.getMembers() here
        // meant a recruited member never got a dissent turn — it could speak in
        // every phase but was structurally unable to register a minority view,
        // which is the one thing the minority report exists to capture.
        List<GroupMember> effectiveRoster = GroupConversationService.rosterWithRecruits(config, gc);
        List<GroupMember> dissenters = effectiveRoster.isEmpty()
                ? List.of()
                : effectiveRoster.stream()
                        .filter(m -> m.agentId() != null && !synthesizerIds.contains(m.agentId()))
                        // A GROUP member's "one short turn" would recurse into an entire
                        // nested sub-discussion, whose concatenated transcript then
                        // becomes the dissent text. That is neither short nor a minority
                        // report, and it costs a full discussion per dissenter.
                        .filter(m -> m.memberType() != AgentGroupConfiguration.MemberType.GROUP)
                        .toList();
        if (dissenters.isEmpty()) {
            return 0;
        }

        // I3: after a judged debate the transcript entry holds the judge's JSON.
        // Asking members to disagree with a serialization format produces DISSENT
        // entries — public, SSE-streamed, and recorded on the DecisionRecord — that
        // argue with braces. The rendered outcome is what a human would have been
        // shown, so it is what the minority reacts to.
        DecisionRecord decision = gc.getDecision();
        String reactTo = DebateVerdictParser.isRenderedFrom(decision, synthesis) ? decision.outcome() : synthesis;
        String input = AbstentionDetector.buildDissentInput(reactTo);
        List<Dissent> collected = new ArrayList<>();
        for (GroupMember member : dissenters) {
            if (maxTurns > 0 && turnCounter != null && turnCounter.get() >= maxTurns) {
                break;
            }
            if (GroupCostLedger.wouldExceedCeiling(gc, protocol)) {
                break;
            }
            if (turnCounter != null) {
                turnCounter.incrementAndGet();
            }
            try {
                // DISSENT_CONVERSATION_KEY_PREFIX + agentId: the member's own
                // conversation would otherwise gain a "critique the synthesis" exchange
                // that a later round (a continuation, or another repeat of an earlier
                // phase) reads back as recent context. Same reasoning as I2's judge.
                TranscriptEntry reply = memberTurnExecutor.executeAgentTurn(member, gc, input, protocol, phaseIdx, phase, null, listener,
                        null, DISSENT_CONVERSATION_KEY_PREFIX + member.agentId());
                String content = reply != null ? reply.content() : null;
                // A PASS here is checked directly rather than through the phase's
                // allowAbstention: the dissent prompt always offers PASS, independent
                // of whether the SYNTHESIS phase itself allows abstention.
                if (content == null || content.isBlank() || AbstentionDetector.isAbstention(content)) {
                    continue;
                }
                // Carries the envelope executeAgentTurn already computed rather than
                // rebuilding bare: a DISSENT is a real member-authored contribution
                // peers can read, so dropping its signature would make it the one
                // entry type a signing-enabled group cannot verify — the same defect
                // the tool-rejection path documents at MemberTurnExecutor's
                // tryResolveMemberToolPause.
                var dissentEntry = new TranscriptEntry(
                        member.agentId(), member.displayName(), content,
                        phaseIdx, phase.name(), TranscriptEntryType.DISSENT, Instant.now(), null, null,
                        reply.signature(), reply.signatureNonce(), reply.signatureTimestampMs(), reply.signatureKeyVersion());
                gc.getTranscript().add(dissentEntry);
                collected.add(new Dissent(member.agentId(), member.displayName(), content));
                if (listener != null) {
                    // Without this the minority report is invisible to every
                    // event-driven surface (SSE, Slack) — the transcript would carry a
                    // dissent nobody watching the discussion ever saw.
                    listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                            member.agentId(), member.displayName(), content, phaseIdx, phase.name()));
                }
            } catch (Exception e) {
                // One member's failure must not cost the others their dissent, and must
                // not fail a discussion that has already produced its synthesis.
                LOGGER.warnf("Dissent turn failed for member '%s' — continuing: %s", member.agentId(), e.getMessage());
            }
        }

        if (!collected.isEmpty()) {
            recordDissents(gc, phase, collected);
        }
        return collected.size();
    }

    /**
     * How many member turns a repeat of {@code phase} schedules — the denominator
     * "did everyone abstain?" must be measured against.
     * <p>
     * A peer-targeted phase runs one turn per (speaker, other-member) pair, so its
     * count is N×(N-1), not N. Sequential and parallel phases run one turn per
     * speaker. Using the speaker count for all three made unanimity mean different
     * things per turn order — the kind of mismatch that reads as correct until a
     * three-member critique round quietly ends itself.
     */
    private static int expectedTurnsFor(DiscussionPhase phase, List<GroupMember> speakers, List<GroupMember> targets) {
        int n = speakers != null ? speakers.size() : 0;
        if (n == 0) {
            return 0;
        }
        if (!phase.targetEachPeer()) {
            return n;
        }
        // Peer-targeted turns are speakers x targets MINUS the self-pairs, and the
        // two lists are not the same list: speakers come from the phase's resolved
        // participants (recruits included, or a ROLE: subset), targets from the
        // configured roster. Assuming n*(n-1) was only correct when they coincided.
        // It over-counted for a ROLE:-scoped phase (2 reviewers of 5 members run 8
        // turns, not 2) and under-counted once I7 let a recruit speak (4 speakers
        // over a 3-member roster run 9 turns, not 12) — and since I4's unanimity
        // test compares an exact count against this number, a wrong denominator
        // either fires the early exit over a round that produced real critiques, or
        // makes it arithmetically unreachable.
        List<GroupMember> targetList = targets != null && !targets.isEmpty() ? targets : speakers;
        int m = targetList.size();
        long selfPairs = speakers.stream()
                .filter(sp -> sp != null && sp.agentId() != null)
                .filter(sp -> targetList.stream().anyMatch(t -> t != null && sp.agentId().equals(t.agentId())))
                .count();
        return (int) (((long) n * m) - selfPairs);
    }

    /** The most recent synthesis text, which is what dissenters are reacting to. */
    private static String latestSynthesis(GroupConversation gc) {
        List<TranscriptEntry> transcript = gc.getTranscript();
        // THIS round's entries only. A continuation re-runs every phase from index 0
        // against a transcript that still holds the previous round's, and an entry
        // carries no round — so an unscoped scan let a round whose synthesis
        // produced nothing (judge undeployed, timed out, abstained, or the ceiling
        // fired) silently adopt the previous round's conclusion: its verdict, the
        // dissents raised against it, and the answer, all attributed to a question
        // this round never answered. Also drops the getSynthesizedAnswer() fallback
        // for the same reason — that field still holds the prior round's answer.
        int from = Math.max(0, Math.min(gc.getRoundStartTranscriptIndex(), transcript.size()));
        synchronized (transcript) {
            for (int i = transcript.size() - 1; i >= from; i--) {
                TranscriptEntry e = transcript.get(i);
                if (e != null && e.type() == TranscriptEntryType.SYNTHESIS && e.content() != null) {
                    return e.content();
                }
            }
        }
        // Only a first round may fall back to the answer field: there is no earlier
        // round for it to have come from.
        return from == 0 ? gc.getSynthesizedAnswer() : null;
    }

    /**
     * Reads a debate judgment out of the phase's synthesis into the structured
     * {@code DecisionRecord} (I3).
     * <p>
     * Called for every SYNTHESIS phase; only a phase that actually produced a
     * judgment writes anything. The check is the same one that chose the judgment
     * template in the first place — if {@code GroupContextBuilder} did not ask for
     * a verdict, there is no JSON to find and a prose synthesis would be recorded
     * as a failed parse, which would be a lie about a phase that was never asked to
     * produce one.
     * <p>
     * Runs BEFORE {@link #runDissentRound} so dissents merge onto the verdict
     * rather than the verdict overwriting a dissent-only record. It also carries
     * any dissents already present, so the two orderings both end up whole.
     * <p>
     * A config with two judged SYNTHESIS phases lets the later one REPLACE the
     * earlier verdict, including replacing a clean verdict with a {@code NONE} when
     * the second judgment fails to parse. That is deliberate: the answer always
     * comes from the last synthesis, so keeping an earlier winner beside a later,
     * different conclusion would hand callers a structured verdict the visible
     * answer contradicts. One conclusion per discussion, and it is the last one.
     *
     * @return {@code true} if a verdict was parsed and recorded
     */
    public boolean recordDebateVerdict(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase, int phaseIdx,
                                       List<GroupMember> synthesizers) {
        GroupMember synthesizer = synthesizers != null && !synthesizers.isEmpty() ? synthesizers.get(0) : null;
        List<GroupMember> members = GroupConversationService.rosterWithRecruits(config, gc);
        if (!contextBuilder.isDebateJudgment(phase, synthesizer, gc.getTranscript(), phaseIdx, members)) {
            return false;
        }
        String judgment = latestSynthesis(gc);
        if (judgment == null || judgment.isBlank()) {
            return false;
        }
        DecisionRecord existing = gc.getDecision();
        DecisionRecord decision = DebateVerdictParser.parse(judgment, phase.name(),
                existing != null ? existing.dissents() : null);
        gc.setDecision(decision);
        if (decision.type() == DecisionType.VERDICT) {
            LOGGER.infof("Group %s recorded a debate verdict at phase '%s': %s", gc.getId(), phase.name(), decision.outcome());
            return true;
        }
        LOGGER.infof("Group %s produced a debate judgment that could not be read as a verdict at phase '%s' — "
                + "keeping the prose conclusion", gc.getId(), phase.name());
        return false;
    }

    /**
     * The conversation key the vote tiebreaker's private conversation lives under
     * (I14). Not the moderator's agent id, for exactly
     * {@link #JUDGE_CONVERSATION_KEY}'s reason: the tiebreak prompt must not become
     * the moderator's recent history.
     */
    static final String TIEBREAK_CONVERSATION_KEY = "__vote_tiebreak";

    /**
     * Tallies a completed VOTE phase into the discussion's {@link DecisionRecord}
     * and fires {@code decision_reached} (I14).
     * <p>
     * Quorum failures and ties go to the phase's {@code tiePolicy}:
     * {@code MODERATOR_DECIDES} runs one moderator turn choosing among the
     * unresolved options (method {@code vote+moderator-tiebreak});
     * {@code NO_DECISION} records the honest {@code NONE}. {@code
     * HUMAN_DECIDES} is save-time rejected until I6 ships human members, so
     * reaching it here means hand-edited storage — it degrades to NO_DECISION with
     * a WARN rather than guessing.
     * <p>
     * Like every decision producer, failure is prose-only, never a failed
     * discussion.
     */
    public void recordVoteDecision(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase, ProtocolConfig protocol,
                                   int phaseIdx, List<TranscriptEntry> repeatEntries, List<GroupMember> speakers,
                                   GroupDiscussionEventListener listener, AtomicInteger turnCounter, int maxTurns) {
        var voteConfig = phase.voteConfig() != null ? phase.voteConfig() : new AgentGroupConfiguration.VoteConfig();
        List<TranscriptEntry> transcriptSnapshot;
        synchronized (gc.getTranscript()) {
            transcriptSnapshot = List.copyOf(gc.getTranscript());
        }
        List<String> options = VoteTallyEngine.resolveOptions(voteConfig, transcriptSnapshot);
        if (options.size() < 2) {
            LOGGER.warnf("Group %s: VOTE phase '%s' resolved %d option(s) — a vote needs at least two; recording NONE",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phase.name()), options.size());
            setDecisionCarryingDissents(gc, new DecisionRecord(DecisionType.NONE,
                    "The vote could not run: only " + options.size() + " option(s) were available.",
                    null, null, List.of(), VoteTallyEngine.METHOD_VOTE, phase.name(), null));
            fireDecisionReached(gc, listener);
            return;
        }

        List<TranscriptEntry> ballots = repeatEntries.stream().filter(e -> e != null && e.type() == TranscriptEntryType.VOTE).toList();
        int participants = speakers != null ? speakers.size() : ballots.size();
        var outcome = VoteTallyEngine.tally(ballots, participants, options, voteConfig, phase.name());

        DecisionRecord decision = outcome.decision();
        if (!outcome.unresolvedOptions().isEmpty()) {
            decision = switch (voteConfig.tiePolicy()) {
                case MODERATOR_DECIDES -> {
                    // The tiebreak is a real LLM call — same two budgets as the
                    // convergence judge and the dissent round. Without this gate a
                    // tied vote issued one uncounted turn even after the discussion
                    // exhausted maxTurns or blew its cost ceiling.
                    if ((maxTurns > 0 && turnCounter != null && turnCounter.get() >= maxTurns)
                            || GroupCostLedger.wouldExceedCeiling(gc, protocol)) {
                        LOGGER.warnf("Group %s: VOTE phase '%s' tied but the turn/cost budget is exhausted — keeping NO_DECISION",
                                LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phase.name()));
                        yield outcome.decision();
                    }
                    if (turnCounter != null) {
                        turnCounter.incrementAndGet();
                    }
                    yield moderatorTiebreak(gc, config, phase, protocol, phaseIdx, outcome, listener);
                }
                case HUMAN_DECIDES -> {
                    LOGGER.warnf("Group %s: VOTE phase '%s' has tiePolicy HUMAN_DECIDES, which needs human group members (I6) — "
                            + "recording NO_DECISION", LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phase.name()));
                    yield outcome.decision();
                }
                case NO_DECISION -> outcome.decision();
            };
        }

        setDecisionCarryingDissents(gc, decision);
        LOGGER.infof("Group %s: VOTE phase '%s' concluded: %s", LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(phase.name()),
                decision.type() == DecisionType.VOTE
                        ? LogSanitizer.sanitize(decision.outcome())
                        : "no decision (" + LogSanitizer.sanitize(decision.outcome()) + ")");
        fireDecisionReached(gc, listener);
    }

    /**
     * One moderator turn choosing among the unresolved options. The reply is
     * resolved by the same exact-scan rule as a tier-2 ballot; an unreadable reply
     * keeps the tally's honest NONE.
     */
    private DecisionRecord moderatorTiebreak(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                                             ProtocolConfig protocol, int phaseIdx, VoteTallyEngine.TallyOutcome outcome,
                                             GroupDiscussionEventListener listener) {
        String moderatorAgentId = config.getModeratorAgentId();
        if (moderatorAgentId == null || moderatorAgentId.isBlank()) {
            LOGGER.warnf("Group %s: tiePolicy MODERATOR_DECIDES but the group names no moderatorAgentId — keeping NO_DECISION",
                    LogSanitizer.sanitize(gc.getId()));
            return outcome.decision();
        }
        String reason = outcome.quorumReached() ? "The vote tied between" : "The vote did not reach quorum; decide among";
        String input = """
                %s these options:
                %s

                As the moderator, choose exactly ONE. Reply with ONLY the exact text of the option you choose.
                """.formatted(reason, outcome.unresolvedOptions().stream().map(o -> "- " + o).collect(Collectors.joining("\n")));
        try {
            var moderator = new GroupMember(moderatorAgentId, "Vote Tiebreaker", 0, "MODERATOR");
            TranscriptEntry reply = memberTurnExecutor.executeAgentTurn(moderator, gc, input, protocol, phaseIdx, phase, null, listener,
                    null, TIEBREAK_CONVERSATION_KEY);
            String choice = VoteTallyEngine.resolveChoice(reply != null ? reply.content() : null, outcome.unresolvedOptions());
            if (choice == null) {
                LOGGER.warnf("Group %s: the tiebreaker's reply named no single option — keeping NO_DECISION",
                        LogSanitizer.sanitize(gc.getId()));
                return outcome.decision();
            }
            DecisionRecord base = outcome.decision();
            // Dissents against the CHOSEN option, from the carried ballots — the
            // unresolved record's own list is necessarily empty, and reusing it
            // dropped the minority report for exactly the closest votes.
            return new DecisionRecord(DecisionType.VOTE,
                    "\"%s\" chosen by the moderator (%s).".formatted(choice,
                            outcome.quorumReached() ? "tie break" : "quorum failure"),
                    choice, base.tally(), VoteTallyEngine.losingDissents(outcome.ballots(), choice),
                    VoteTallyEngine.METHOD_TIEBREAK, phase.name(), reply.content());
        } catch (Exception e) {
            LOGGER.warnf("Group %s: vote tiebreak failed (%s) — keeping NO_DECISION",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(e.getMessage()));
            return outcome.decision();
        }
    }

    /** Immutable-record merge: a new decision keeps dissents already collected. */
    private static void setDecisionCarryingDissents(GroupConversation gc, DecisionRecord decision) {
        DecisionRecord existing = gc.getDecision();
        if (existing != null && existing.dissents() != null && !existing.dissents().isEmpty()
                && (decision.dissents() == null || decision.dissents().isEmpty())) {
            decision = new DecisionRecord(decision.type(), decision.outcome(), decision.winner(), decision.tally(),
                    existing.dissents(), decision.method(), decision.decidedAtPhase(), decision.raw());
        }
        gc.setDecision(decision);
    }

    /**
     * The {@code decision_reached} producer (the §4 gap, folded in with I14): fired
     * whenever a {@link DecisionRecord} lands on the discussion. The Slack listener
     * skips {@code NONE} records itself; SSE forwards everything.
     */
    public void fireDecisionReached(GroupConversation gc, GroupDiscussionEventListener listener) {
        if (listener != null && gc.getDecision() != null) {
            listener.onDecisionReached(new GroupConversationEventSink.DecisionReachedEvent(gc.getDecision()));
        }
    }

    /**
     * Merges dissents into the discussion's {@code DecisionRecord}, creating a
     * {@code NONE}-type record when no decision-producing feature ran. Records are
     * immutable, so an existing one is rebuilt rather than mutated.
     */
    private static void recordDissents(GroupConversation gc, DiscussionPhase phase, List<Dissent> collected) {
        DecisionRecord existing = gc.getDecision();
        if (existing == null) {
            gc.setDecision(new DecisionRecord(DecisionType.NONE, null, null, null, List.copyOf(collected),
                    "dissent-round", phase.name(), null));
            return;
        }
        // Appends rather than replaces: a verdict or vote may already carry dissents
        // from its own mechanism, and this round adds to the minority view rather
        // than being the only source of it.
        var merged = new ArrayList<Dissent>(existing.dissents() != null ? existing.dissents() : List.of());
        merged.addAll(collected);
        gc.setDecision(new DecisionRecord(existing.type(), existing.outcome(), existing.winner(), existing.tally(),
                List.copyOf(merged), existing.method(), existing.decidedAtPhase(), existing.raw()));
    }

    private ConvergenceDetector.ConvergenceVerdict runJudge(GroupConversation gc, AgentGroupConfiguration config, DiscussionPhase phase,
                                                            ProtocolConfig protocol, int phaseIdx, ConvergenceConfig convergence,
                                                            List<TranscriptEntry> repeatEntries, List<TranscriptEntry> previousRepeatEntries,
                                                            GroupDiscussionEventListener listener) {
        String moderatorAgentId = config.getModeratorAgentId();
        if (moderatorAgentId == null || moderatorAgentId.isBlank()) {
            // Nothing to run the judge on. Not an error: a phase can enable
            // convergence on a group that has no moderator, and the honest outcome
            // is to keep going rather than to guess.
            return ConvergenceDetector.ConvergenceVerdict.notConverged("Convergence judge skipped — no moderator agent configured");
        }
        if (ConvergenceConfig.JUDGE_SERVICE.equals(convergence.judge())) {
            LOGGER.warnf("Phase '%s' requests judge=SERVICE, which is not wired yet — falling back to the moderator agent. "
                    + "See ConvergenceConfig's Javadoc.", phase.name());
        }

        var judge = new GroupMember(moderatorAgentId, "Convergence Judge", 0, "MODERATOR");
        String input = ConvergenceDetector.buildJudgeInput(previousRepeatEntries, repeatEntries);
        try {
            // JUDGE_CONVERSATION_KEY, not the moderator's agent id: the judge runs the
            // moderator AGENT but must not share the moderator's CONVERSATION, or its
            // "reply with ONLY this JSON" prompts become the recent history a later
            // SYNTHESIS turn (same agent) reads — and the synthesis comes back as JSON.
            TranscriptEntry judgeEntry = memberTurnExecutor.executeAgentTurn(judge, gc, input, protocol, phaseIdx, phase, null, listener,
                    null, JUDGE_CONVERSATION_KEY);
            return ConvergenceDetector.parseJudgeVerdict(judgeEntry != null ? judgeEntry.content() : null, convergence.threshold());
        } catch (Exception e) {
            LOGGER.warnf("Convergence judge failed for phase '%s' — continuing without an early exit: %s", phase.name(), e.getMessage());
            return ConvergenceDetector.ConvergenceVerdict.notConverged("Convergence judge failed: " + e.getMessage());
        }
    }

    /**
     * Writes the {@code CONVERGENCE} transcript entry and fires the events. The
     * entry is peer-hidden by F4's visibility matrix, so it informs observers and
     * the audit trail without becoming something the next speaker reacts to.
     */
    private void recordConvergence(GroupConversation gc, DiscussionPhase phase, int phaseIdx, int repeat, double score, boolean converged,
                                   String reason, GroupDiscussionEventListener listener) {
        gc.getTranscript().add(new TranscriptEntry(
                null, "System", reason, phaseIdx, phase.name(),
                TranscriptEntryType.CONVERGENCE, Instant.now(), null, null));
        if (listener == null) {
            return;
        }
        listener.onConvergenceChecked(new GroupConversationEventSink.ConvergenceCheckedEvent(
                phaseIdx, phase.name(), repeat, score, converged, reason));
        if (converged) {
            int repeatsSkipped = Math.max(0, Math.max(phase.repeats(), 1) - (repeat + 1));
            listener.onConvergenceReached(new GroupConversationEventSink.ConvergenceReachedEvent(
                    phaseIdx, phase.name(), repeat, repeatsSkipped, reason));
        }
    }

    /**
     * @param startSpeakerIdx
     *            index into {@code speakers} to resume from (Wave 0, F2) — 0 for
     *            every normal call. A speaker-level HITL pause (I6) bookmarks the
     *            index it landed on in {@code GroupConversation#resumePoint};
     *            {@code executeDiscussion} reads that back and passes it here on
     *            the resumed leg so speakers before it are not re-run. Out-of-range
     *            values (a config edited to remove members while paused) clamp to
     *            {@code speakers.size()} — i.e. the phase produces zero turns
     *            rather than an {@code IndexOutOfBoundsException}; catching that
     *            drift before it gets this far is {@code GroupHitlCoordinator}'s
     *            job — see its bookmark-drift validation.
     */
    public void executeSequentialPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                       ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                       AtomicInteger turnCounter, int maxTurns, int startSpeakerIdx)
            throws GroupDiscussionException {
        int from = Math.min(Math.max(startSpeakerIdx, 0), speakers.size());
        for (int idx = from; idx < speakers.size(); idx++) {
            GroupMember speaker = speakers.get(idx);
            if (turnCounter.get() >= maxTurns) {
                break;
            }
            // I1: checked before each turn — an already-dispatched turn may still
            // overshoot the ceiling, which is accepted (see GroupCostLedger's Javadoc).
            if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
                break;
            }
            // I6: a human's turn pauses the discussion. AFTER the budget checks —
            // an exhausted budget means no more turns, the human's included — and
            // BEFORE the turn count (the pause commit accounts for the turn, so a
            // restored pause does not double-count it). The prompt is rendered
            // HERE, against exactly the transcript an agent speaker would see.
            if (speaker.memberType() == AgentGroupConfiguration.MemberType.HUMAN) {
                String humanInput = contextBuilder.buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, null,
                        GroupConversationService.rosterWithRecruits(config, gc), config.getContextWindow(), gc,
                        config.getRetroConfig());
                throw new HumanTurnRequired(speaker, idx, humanInput, false);
            }
            turnCounter.incrementAndGet();
            if (listener != null) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
            }
            String input = contextBuilder.buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, null,
                    GroupConversationService.rosterWithRecruits(config, gc), config.getContextWindow(), gc,
                    config.getRetroConfig());
            // I11: the negotiation table lives on gc, which buildPhaseInput does
            // not see — appended here (no-op for non-negotiation phases).
            input = NegotiationEngine.appendStateIfRelevant(input, gc, phase);
            TranscriptEntry entry = memberTurnExecutor.executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null, listener);
            gc.getTranscript().add(entry);
            if (listener != null) {
                listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(speaker.agentId(), speaker.displayName(),
                        entry.content(), phaseIdx, phase.name()));
            }
        }
    }

    public void executeParallelPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                     ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                     AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns, null);
    }

    /**
     * @param humanResumeIdx
     *            {@code null} for every fresh run. Non-null only when resuming an
     *            I6 {@code HUMAN_TURN_PARALLEL} pause: the agent fan-out already
     *            ran before the pause, so the resumed leg skips straight to the
     *            phase's human tail, starting at this index into the human-only
     *            sublist (see {@code GroupConversation.ResumePoint}).
     */
    public void executeParallelPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                     ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                     AtomicInteger turnCounter, int maxTurns, Integer humanResumeIdx)
            throws GroupDiscussionException {

        // I6: humans never join the fan-out — an LLM answers in seconds, a human
        // in minutes-to-days, and a paused future would pin the whole batch.
        // Agents run first (concurrently), then humans are prompted sequentially,
        // each pausing the discussion in turn.
        List<GroupMember> humans = speakers.stream()
                .filter(s -> s.memberType() == AgentGroupConfiguration.MemberType.HUMAN).toList();
        List<GroupMember> agentSpeakers = humans.isEmpty()
                ? speakers
                : speakers.stream().filter(s -> s.memberType() != AgentGroupConfiguration.MemberType.HUMAN).toList();

        if (humanResumeIdx != null) {
            // Resumed leg: the fan-out already ran before the pause. The remaining
            // humans' prompts must stay blind to their peers — the fresh leg uses
            // the pre-fan-out snapshot for that, which no longer exists here, so
            // the resumed bound is "no entries of this phase at all" (deliberately
            // a touch stronger for repeats > 1 than the fresh-leg bound; a blind
            // round that stays blind is the honest failure direction).
            List<TranscriptEntry> blindTranscript;
            synchronized (gc.getTranscript()) {
                blindTranscript = gc.getTranscript().stream()
                        .filter(e -> e == null || e.phaseIndex() != phaseIdx).toList();
            }
            promptHumanTail(gc, config, humans, phase, protocol, question, phaseIdx, turnCounter, maxTurns,
                    humanResumeIdx, blindTranscript);
            return;
        }

        // I1: whole-batch check — a PARALLEL phase fans every speaker out at once,
        // so there is no per-speaker checkpoint to gate individually; this is the
        // coarsest granularity the spec's "an in-flight turn may overshoot" already
        // accepts.
        if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
            return;
        }

        // Cap batch size to remaining turn budget
        int remainingTurns = maxTurns > 0 ? Math.max(0, maxTurns - turnCounter.get()) : agentSpeakers.size();
        if (remainingTurns == 0) {
            return;
        }
        List<GroupMember> batchSpeakers = maxTurns > 0
                ? agentSpeakers.subList(0, Math.min(agentSpeakers.size(), remainingTurns))
                : agentSpeakers;

        // SAFETY: Snapshot the transcript so parallel tasks each see a consistent view.
        // Iterating a Collections.synchronizedList requires holding its monitor.
        //
        // The bare gc.getTranscript().add(...) calls further down this method are NOT
        // an oversight, and reviewers have asked about the asymmetry: GroupConversation
        // guarantees the transcript is always a Collections.synchronizedList (both the
        // field initializer and setTranscript wrap it — no path assigns a bare list),
        // and that wrapper's mutex IS the wrapper object, i.e. exactly what this block
        // locks. So add() and this snapshot already exclude one another; the explicit
        // monitor is required only because List.copyOf ITERATES, which the wrapper
        // cannot make atomic on its own. Wrapping every append would add lock scope
        // without removing a race.
        //
        // This is deliberately the opposite conclusion from the taskList guard in the
        // task-execution wave, where the asymmetry WAS a real bug: there the two sides
        // were a cancellation read and a document write ordered only by the monitor,
        // not two operations on one synchronized collection.
        List<TranscriptEntry> snapshotTranscript;
        synchronized (gc.getTranscript()) {
            snapshotTranscript = List.copyOf(gc.getTranscript());
        }

        // Cooperative cancellation for this batch — cancel(true) does not stop a
        // supplyAsync body, so a "cancelled" speaker would otherwise keep running.
        var cancellation = new MemberTurnCancellation();

        // Notify all speakers starting (parallel)
        if (listener != null) {
            for (GroupMember speaker : batchSpeakers) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
            }
        }

        // Each speaker fans out to a further virtual thread; a ThreadLocal does not
        // follow, so carry the caller explicitly. captureOrCurrent, not current: a
        // synchronous discuss() runs on the REST thread, where nothing has bound a
        // caller yet and only the request can supply one.
        final var phaseCaller = callerIdentityContext.captureOrCurrent();
        List<CompletableFuture<TranscriptEntry>> futures = batchSpeakers.stream()
                .map(speaker -> CompletableFuture.supplyAsync(callerIdentityContext.withIdentitySupplying(phaseCaller, () -> {
                    try {
                        String input = contextBuilder.buildPhaseInput(phase, speaker, question, snapshotTranscript, phaseIdx, null,
                                GroupConversationService.rosterWithRecruits(config, gc), config.getContextWindow(), gc,
                                config.getRetroConfig());
                        // I11: see the sequential loop — appended, not templated.
                        input = NegotiationEngine.appendStateIfRelevant(input, gc, phase);
                        return memberTurnExecutor.executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null, listener, cancellation);
                    } catch (MemberTurnCancelledException e) {
                        // The orchestrator stopped waiting for this batch — surface the
                        // cancellation instead of fabricating a contribution for it.
                        // Must stay ABOVE the Exception catch, which would otherwise
                        // convert a cancellation into an error transcript entry.
                        throw new CompletionException(e);
                    } catch (GroupDiscussionException e) {
                        if (e.getCause() instanceof QuotaExceededException) {
                            throw new CompletionException(e);
                        }
                        LOGGER.errorf("Parallel phase failed for %s: %s", speaker.agentId(), e.getMessage());
                        return memberTurnExecutor.errorEntry(speaker, phaseIdx, phase, e.getMessage());
                    } catch (Exception e) {
                        LOGGER.errorf("Parallel phase failed for %s: %s", speaker.agentId(), e.getMessage());
                        return memberTurnExecutor.errorEntry(speaker, phaseIdx, phase, e.getMessage());
                    }
                }), executorService)).toList();

        // ONE deadline for the whole batch: these turns run concurrently, so giving
        // every get() the full budget in turn made the worst case N × timeout
        // (10 members × 180s = 30 minutes) instead of the configured timeout. The
        // budget stays independent of the batch size — that is the point — but it has
        // to cover what a SINGLE member is allowed to take: its per-attempt timeout
        // times the attempts onAgentFailure grants it, plus a grace for the setup it
        // does before reaching its own await point. Armed at exactly one attempt, the
        // orchestrator won every race: it cancelled the batch while members were still
        // inside their own budget, so executeAgentTurn's TimeoutException branch —
        // which owns the RETRY and ABORT policies — was unreachable in parallel phases
        // and every member timeout became an unattributed SKIPPED "unknown" entry.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(GroupConversationService.parallelBatchBudgetSeconds(protocol));
        for (int i = 0; i < futures.size(); i++) {
            try {
                long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
                TranscriptEntry entry = futures.get(i).get(remainingNanos, TimeUnit.NANOSECONDS);
                gc.getTranscript().add(entry);
                if (listener != null) {
                    listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(entry.speakerAgentId(), entry.speakerDisplayName(),
                            entry.content(), phaseIdx, phase.name()));
                }
            } catch (TimeoutException e) {
                // The batch deadline passed — release every speaker still waiting on a
                // response, not just this one.
                cancellation.cancel();
                // KNOWN BOUND, deliberately not "fixed" here: because the deadline is
                // already spent, every later speaker's get() returns immediately, so a
                // member that finishes a millisecond late has its real entry — already
                // computed, signed and cost-attributed — replaced by this SKIPPED one,
                // and its body runs on into the next phase.
                //
                // A bounded drain after cancel() looks like the fix and is not: it
                // extends the phase past the very deadline that exists to bound it
                // (parallelBatchBudgetSeconds), which
                // parallelPhase_appliesOneDeadlineAcrossAllMembers correctly rejects —
                // it caught a 3s budget becoming 8s. Cancelling the futures instead
                // makes get() throw CancellationException, which neither catch here
                // handles, so those speakers lose their SKIPPED entry entirely; that
                // same test caught 5 entries becoming 1. Containing the orphan's
                // WRITES is what actually matters, and MemberTurnExecutor's response
                // callback now drops its gc mutations once cancellation is observed.
                // Recovering the late entry needs the deadline contract renegotiated,
                // which is its own change.
                gc.getTranscript().add(new TranscriptEntry("unknown", "Unknown", null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Timeout", null));
            } catch (ExecutionException e) {
                // Unwrap: CompletionException → GroupDiscussionException →
                // QuotaExceededException
                Throwable cause = e.getCause();
                if (cause instanceof CompletionException ce) {
                    cause = ce.getCause();
                }
                if (cause instanceof MemberTurnCancelledException) {
                    // Already released by the batch deadline above — same outcome as a
                    // speaker whose own get() timed out.
                    gc.getTranscript().add(new TranscriptEntry("unknown", "Unknown", null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                            Instant.now(), "Timeout", null));
                    continue;
                }
                if (cause instanceof GroupDiscussionException gde
                        && gde.getCause() instanceof QuotaExceededException) {
                    // Release the remaining speakers and propagate
                    cancellation.cancel();
                    throw gde;
                }
                gc.getTranscript().add(memberTurnExecutor.errorEntry(null, phaseIdx, phase, e.getMessage()));
            } catch (Exception e) {
                gc.getTranscript().add(memberTurnExecutor.errorEntry(null, phaseIdx, phase, e.getMessage()));
            }
        }
        // Count all completed turns for this batch (parallel turns are atomic batches)
        turnCounter.addAndGet(batchSpeakers.size());

        // I6: the human tail, prompted against the PRE-fan-out snapshot so a
        // "parallel" (independent) round stays independent — a human answering
        // after the agents must not read their answers first.
        promptHumanTail(gc, config, humans, phase, protocol, question, phaseIdx, turnCounter, maxTurns, 0, snapshotTranscript);
    }

    /**
     * Prompts a PARALLEL phase's HUMAN members one at a time, from
     * {@code startIdx}. Throws {@link HumanTurnRequired} for the first human whose
     * turn is still owed — one pause per human, sequentially. Budget checks mirror
     * the sequential loop: an exhausted budget owes no more turns, human or not.
     */
    private void promptHumanTail(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> humans, DiscussionPhase phase,
                                 ProtocolConfig protocol, String question, int phaseIdx, AtomicInteger turnCounter, int maxTurns,
                                 int startIdx, List<TranscriptEntry> promptTranscript) {
        for (int i = Math.max(0, startIdx); i < humans.size(); i++) {
            if (maxTurns > 0 && turnCounter.get() >= maxTurns) {
                return;
            }
            if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
                return;
            }
            GroupMember human = humans.get(i);
            String input = contextBuilder.buildPhaseInput(phase, human, question, promptTranscript, phaseIdx, null,
                    GroupConversationService.rosterWithRecruits(config, gc), config.getContextWindow(), gc,
                    config.getRetroConfig());
            throw new HumanTurnRequired(human, i, input, true);
        }
    }

    /**
     * Peer-targeted phase: each speaker addresses each OTHER speaker individually
     * (N×(N-1) turns). Used for CRITIQUE style.
     */
    public void executePeerTargetedPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                         ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                         AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        // Every configured member is a candidate target, in speaking order. The
        // comment here used to say "all non-moderator members"; there is no
        // moderator filter and never was, so a configured moderatorAgentId does get
        // peer-critiqued. Whether it should is a design question for the group
        // config, not something to change silently inside an extraction — the
        // comment is corrected to match the code rather than the reverse.
        List<GroupMember> allMembers = config.getMembers().stream()
                .sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).toList();

        outer : for (GroupMember speaker : speakers) {
            for (GroupMember target : allMembers) {
                if (speaker.agentId().equals(target.agentId())) {
                    continue; // Don't critique yourself
                }
                if (turnCounter.get() >= maxTurns) {
                    break outer;
                }
                if (GroupCostLedger.enforceCeiling(gc, protocol, phaseIdx, phase)) {
                    break outer;
                }
                turnCounter.incrementAndGet();
                if (listener != null) {
                    listener.onSpeakerStart(
                            new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
                }
                String input = contextBuilder.buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, target,
                        GroupConversationService.rosterWithRecruits(config, gc), config.getContextWindow(), gc,
                        config.getRetroConfig());
                TranscriptEntry entry = memberTurnExecutor.executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, target.agentId(),
                        listener);
                gc.getTranscript().add(entry);
                if (listener != null) {
                    listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(speaker.agentId(), speaker.displayName(),
                            entry.content(), phaseIdx, phase.name(), target.agentId(), target.displayName()));
                }
            }
        }
    }
}
