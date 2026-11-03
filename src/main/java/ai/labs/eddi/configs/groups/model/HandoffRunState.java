/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What one group discussion knows about cross-team handoff, carried on the
 * persisted {@link GroupConversation} (precedent: {@code negotiation}).
 * <ul>
 * <li><b>Lineage</b> ({@link #hop}, {@link #lineage}): stamped by the team
 * cadence at fire time from the origins of the tasks it pulled, so a handoff
 * this discussion files knows how many team boundaries its work has already
 * crossed. Zero hops and an empty chain for every other discussion.</li>
 * <li><b>The per-run ledger</b> ({@link #used}): handoffs this discussion has
 * filed or has in flight, reserved atomically under one monitor because a
 * PARALLEL phase runs members, and so their tools, concurrently. Persisted so a
 * pause and resume does not reset the cap.</li>
 * <li><b>A bounded record</b> ({@link #filed}) that makes the source-side
 * outcome visible on the discussion payload.</li>
 * <li><b>A transient event queue</b>: tools have no listener reference, so a
 * filed handoff rides this queue until the turn executor drains it into the
 * {@code team_handoff} SSE event.</li>
 * </ul>
 * <b>Opt-in by absence:</b> {@code GroupConversation#getHandoffState()} is
 * {@code null} until something needs it, and an old document reads as hop 0.
 *
 * @since 6.7.0
 */
public class HandoffRunState {

    /** Entries kept in {@link #filed}. */
    public static final int MAX_FILED = 50;

    /**
     * One handoff this discussion made.
     *
     * @param outcome
     *            the {@code HandoffResult.Outcome} name
     */
    public record FiledHandoff(String handoffId, String targetAlias, String targetGroupId, String outcome, String subject) {
    }

    /**
     * An event waiting for the turn executor to announce it.
     *
     * @param mode
     *            {@code AUTO}/{@code REVIEW}, {@code null} when it never got that
     *            far
     * @param trigger
     *            {@code TOOL} or {@code DECISION}
     */
    public record PendingEvent(String handoffId, String targetAlias, String targetGroupId, String outcome, String mode, String trigger,
            String subject) {
    }

    private int hop;
    private List<String> lineage = new ArrayList<>();
    private int used;
    private List<FiledHandoff> filed = new ArrayList<>();

    @JsonIgnore
    private final transient Queue<PendingEvent> pendingEvents = new ConcurrentLinkedQueue<>();

    public synchronized int getHop() {
        return hop;
    }

    public synchronized void setHop(int hop) {
        this.hop = Math.max(0, hop);
    }

    public synchronized List<String> getLineage() {
        return List.copyOf(lineage);
    }

    public synchronized void setLineage(List<String> lineage) {
        this.lineage = lineage != null ? new ArrayList<>(lineage) : new ArrayList<>();
    }

    public synchronized int getUsed() {
        return used;
    }

    public synchronized void setUsed(int used) {
        this.used = Math.max(0, used);
    }

    public synchronized List<FiledHandoff> getFiled() {
        return List.copyOf(filed);
    }

    public synchronized void setFiled(List<FiledHandoff> filed) {
        this.filed = filed != null ? new ArrayList<>(filed) : new ArrayList<>();
    }

    /**
     * Takes one slot of the discussion's handoff cap. Atomic, so concurrent members
     * cannot overshoot it.
     *
     * @return {@code false} when {@code cap} slots are already taken
     */
    public synchronized boolean tryReserve(int cap) {
        if (used >= cap) {
            return false;
        }
        used++;
        return true;
    }

    /** Gives back a slot taken by {@link #tryReserve}; never below zero. */
    public synchronized void release() {
        if (used > 0) {
            used--;
        }
    }

    /** Records an outcome, keeping the newest {@value #MAX_FILED}. */
    public synchronized void recordFiled(FiledHandoff entry) {
        filed.add(entry);
        while (filed.size() > MAX_FILED) {
            filed.remove(0);
        }
    }

    /** Queues an event for the turn executor. */
    public void queueEvent(PendingEvent event) {
        if (event != null) {
            pendingEvents.add(event);
        }
    }

    /** Takes everything queued so far, in order. */
    public List<PendingEvent> drainEvents() {
        List<PendingEvent> drained = new ArrayList<>();
        PendingEvent next;
        while ((next = pendingEvents.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }
}
