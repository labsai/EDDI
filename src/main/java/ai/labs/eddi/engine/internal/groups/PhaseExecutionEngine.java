/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
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
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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

    public PhaseExecutionEngine(MemberTurnExecutor memberTurnExecutor, GroupContextBuilder contextBuilder,
            ExecutorService executorService, CallerIdentityContext callerIdentityContext) {
        this.memberTurnExecutor = memberTurnExecutor;
        this.contextBuilder = contextBuilder;
        this.executorService = executorService;
        this.callerIdentityContext = callerIdentityContext;
    }

    public void executeSequentialPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                       ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                       AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns, 0);
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
        for (GroupMember speaker : speakers.subList(from, speakers.size())) {
            if (turnCounter.get() >= maxTurns) {
                break;
            }
            turnCounter.incrementAndGet();
            if (listener != null) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
            }
            String input = contextBuilder.buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, null, config.getMembers());
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

        // Cap batch size to remaining turn budget
        int remainingTurns = maxTurns > 0 ? Math.max(0, maxTurns - turnCounter.get()) : speakers.size();
        if (remainingTurns == 0) {
            return;
        }
        List<GroupMember> batchSpeakers = maxTurns > 0
                ? speakers.subList(0, Math.min(speakers.size(), remainingTurns))
                : speakers;

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
                                config.getMembers());
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
                turnCounter.incrementAndGet();
                if (listener != null) {
                    listener.onSpeakerStart(
                            new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
                }
                String input = contextBuilder.buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, target, config.getMembers());
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
