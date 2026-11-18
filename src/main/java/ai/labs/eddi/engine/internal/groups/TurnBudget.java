/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;

import java.time.Instant;
import java.util.List;

/**
 * The {@code protocol.maxTurns} budget of one discussion leg (K3 of the
 * November 2026 review).
 * <p>
 * The cap used to apply to every phase alike, so a discussion whose opinion
 * rounds used exactly {@code maxTurns} (10 members × 5 rounds = the default 50)
 * never reached its synthesis and completed with no answer and no note. One
 * turn per remaining {@code SYNTHESIS} phase is now reserved: the phases before
 * it stop that many turns earlier. And whenever the cap cuts a discussion
 * short, one {@code System} {@code SKIPPED} entry per round says so.
 */
public final class TurnBudget {

    /**
     * The start of the note the cap leaves in the transcript; also how
     * {@link #wasCut} finds it.
     */
    public static final String CUT_NOTE_PREFIX = "Turn limit (protocol.maxTurns) reached";

    /** {@code protocol.maxTurns} when it is not set (0). */
    public static final int DEFAULT_MAX_TURNS = 50;

    private TurnBudget() {
    }

    /**
     * The cap of a whole leg: the configured {@code maxTurns} (default 50), and for
     * a nested discussion no more than the turns its parent had left (K8). A child
     * used to start with a fresh 50 at every depth.
     */
    public static int legCap(int configuredMaxTurns, Integer inheritedMaxTurns) {
        int own = configuredMaxTurns > 0 ? configuredMaxTurns : DEFAULT_MAX_TURNS;
        return inheritedMaxTurns == null ? own : Math.max(0, Math.min(own, inheritedMaxTurns));
    }

    /**
     * The cap the phase at {@code phaseIdx} runs under: {@code maxTurns} for a
     * {@code SYNTHESIS} phase, otherwise {@code maxTurns} minus one turn per
     * {@code SYNTHESIS} phase after it — never below one turn.
     */
    public static int phaseCap(int maxTurns, List<DiscussionPhase> phases, int phaseIdx) {
        if (phases == null || phaseIdx < 0 || phaseIdx >= phases.size()) {
            return maxTurns;
        }
        DiscussionPhase phase = phases.get(phaseIdx);
        if (phase != null && phase.type() == PhaseType.SYNTHESIS) {
            return maxTurns;
        }
        int reserve = 0;
        for (int i = phaseIdx + 1; i < phases.size(); i++) {
            if (phases.get(i) != null && phases.get(i).type() == PhaseType.SYNTHESIS) {
                reserve++;
            }
        }
        return maxTurns - Math.min(reserve, Math.max(0, maxTurns - 1));
    }

    /**
     * Records — once per round — that the turn cap cut the discussion short. Safe
     * to call from every place that stops for the cap.
     */
    public static void noteCut(GroupConversation gc, int phaseIdx, String phaseName) {
        synchronized (gc.getTranscript()) {
            if (wasCut(gc)) {
                return;
            }
            gc.getTranscript().add(new TranscriptEntry(null, "System", null, phaseIdx, phaseName, TranscriptEntryType.SKIPPED,
                    Instant.now(), CUT_NOTE_PREFIX + " — the remaining discussion turns were skipped", null));
        }
    }

    /** Whether the cap cut the current round short (see {@link #noteCut}). */
    public static boolean wasCut(GroupConversation gc) {
        synchronized (gc.getTranscript()) {
            List<TranscriptEntry> transcript = gc.getTranscript();
            int from = Math.max(0, Math.min(gc.getRoundStartTranscriptIndex(), transcript.size()));
            for (int i = from; i < transcript.size(); i++) {
                TranscriptEntry e = transcript.get(i);
                if (e != null && e.type() == TranscriptEntryType.SKIPPED && e.speakerAgentId() == null && e.errorReason() != null
                        && e.errorReason().startsWith(CUT_NOTE_PREFIX)) {
                    return true;
                }
            }
            return false;
        }
    }
}
