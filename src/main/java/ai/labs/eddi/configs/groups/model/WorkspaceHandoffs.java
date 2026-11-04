/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The cross-team handoff state of one workspace: where each handed-off backlog
 * task came from, the intake inbox of REVIEW items awaiting a human, and
 * tombstones for declined items.
 * <p>
 * <b>Why a side structure and not a {@code TaskItem} component.</b> The cadence
 * writeback rebuilds backlog tasks with the shorter positional constructors,
 * which silently drop trailing components (it already loses
 * {@code createdByAgentId}); provenance stored on the task would vanish after
 * its first run. A map keyed by task id survives that, and avoids touching the
 * ~30 positional call sites of the record.
 * <p>
 * <b>Invariant: an inbox item is not work.</b> It is in no
 * {@link SharedTaskList}, so no cadence can pull it; the only path from the
 * inbox to the backlog is the accept endpoint, which a human with EDIT on the
 * receiving team drives.
 * <p>
 * <b>No end-user id is stored here.</b> The source conversation id is a
 * pointer, not personal data; the acting user of a handoff or of an intake
 * decision goes only into the audit entry, which already has GDPR erasure
 * handling.
 * <p>
 * Every write goes through {@code IGroupWorkspaceStore.casRevision} with the
 * rest of the workspace, so two concurrent filers cannot drop each other.
 *
 * @since 6.7.0
 */
public class WorkspaceHandoffs {

    /** Tombstones kept; the oldest fall off first. */
    public static final int MAX_DECLINED = 200;
    /** Longest lineage chain recorded on an origin. */
    public static final int MAX_LINEAGE = 8;

    /** Who filed: a standalone agent or a group. */
    public enum SourceKind {
        AGENT, GROUP;

        /** The {@code accept.from[].source} spelling of {@code id}. */
        public String key(String id) {
            return name().toLowerCase(Locale.ROOT) + ":" + id;
        }
    }

    /** What caused the handoff. */
    public enum Trigger {
        /** The {@code handoff_to_team} tool. */
        TOOL,
        /** A {@code handoffConfig.onDecision} rule on discussion completion. */
        DECISION
    }

    /** How the task entered this team's backlog. */
    public enum Intake {
        /** Filed straight to the backlog (accept mode AUTO). */
        AUTO,
        /** Waiting in the inbox; not yet work. */
        PENDING,
        /** A human accepted it as filed. */
        APPROVED,
        /** A human accepted it after changing the subject, description or priority. */
        APPROVED_EDITED
    }

    /**
     * Where a handed-off task came from.
     *
     * @param handoffId
     *            stable id of the handoff (also the inbox item id)
     * @param sourceKind
     *            {@link SourceKind#AGENT} or {@link SourceKind#GROUP}
     * @param sourceId
     *            the agent or group config id, derived by the engine, never by the
     *            model
     * @param sourceVersion
     *            the source agent's version when known
     * @param sourceConversationId
     *            the conversation (or group conversation) that filed it
     * @param sourceMemberAgentId
     *            the member agent that called the tool, inside a group
     * @param trigger
     *            tool call or decision rule
     * @param decisionRef
     *            {@code "VOTE@<phase>"} for a decision rule
     * @param artifactId
     *            the shared artifact {@code reference} resolved to, when it did
     * @param reference
     *            the free-text reference the filer gave (artifact name, decision)
     * @param hop
     *            how many teams this work has crossed, including this one
     * @param lineage
     *            source keys the work passed through, oldest first, capped at
     *            {@value #MAX_LINEAGE}
     * @param dedupeKey
     *            what makes a second identical handoff a no-op
     * @param filedAt
     *            when it was filed
     * @param intake
     *            how it entered the backlog
     * @param reviewedAt
     *            when a human decided, for REVIEW items
     */
    public record HandoffOrigin(String handoffId, SourceKind sourceKind, String sourceId, Integer sourceVersion, String sourceConversationId,
            String sourceMemberAgentId, Trigger trigger, String decisionRef, String artifactId, String reference, int hop, List<String> lineage,
            String dedupeKey, Instant filedAt, Intake intake, Instant reviewedAt) {

        public HandoffOrigin {
            lineage = lineage == null ? List.of() : List.copyOf(lineage);
        }

        /** The {@code accept.from[].source} spelling of this origin's source. */
        @JsonIgnore
        public String sourceKey() {
            return sourceKind == null ? null : sourceKind.key(sourceId);
        }

        /** This origin after a human decided. */
        public HandoffOrigin reviewed(Intake decided, Instant at) {
            return new HandoffOrigin(handoffId, sourceKind, sourceId, sourceVersion, sourceConversationId, sourceMemberAgentId, trigger,
                    decisionRef, artifactId, reference, hop, lineage, dedupeKey, filedAt, decided, at);
        }
    }

    /** One REVIEW item waiting for a human. */
    public record InboxItem(String handoffId, String subject, String description, int priority, HandoffOrigin origin) {
    }

    private Map<String, HandoffOrigin> origins = new LinkedHashMap<>();
    private List<InboxItem> inbox = new ArrayList<>();
    private Map<String, Instant> declined = new LinkedHashMap<>();

    public synchronized Map<String, HandoffOrigin> getOrigins() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(origins));
    }

    public synchronized void setOrigins(Map<String, HandoffOrigin> origins) {
        this.origins = origins != null ? new LinkedHashMap<>(origins) : new LinkedHashMap<>();
    }

    public synchronized List<InboxItem> getInbox() {
        return List.copyOf(inbox);
    }

    public synchronized void setInbox(List<InboxItem> inbox) {
        this.inbox = inbox != null ? new ArrayList<>(inbox) : new ArrayList<>();
    }

    public synchronized Map<String, Instant> getDeclined() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(declined));
    }

    public synchronized void setDeclined(Map<String, Instant> declined) {
        this.declined = declined != null ? new LinkedHashMap<>(declined) : new LinkedHashMap<>();
    }

    /** The origin of backlog task {@code taskId}, or {@code null}. */
    public synchronized HandoffOrigin originOf(String taskId) {
        return taskId == null ? null : origins.get(taskId);
    }

    public synchronized void putOrigin(String taskId, HandoffOrigin origin) {
        origins.put(taskId, origin);
    }

    public synchronized void replaceOrigin(String taskId, HandoffOrigin origin) {
        origins.replace(taskId, origin);
    }

    public synchronized HandoffOrigin removeOrigin(String taskId) {
        return origins.remove(taskId);
    }

    /** The inbox item {@code handoffId}, or {@code null}. */
    public synchronized InboxItem findInbox(String handoffId) {
        for (InboxItem item : inbox) {
            if (item.handoffId().equals(handoffId)) {
                return item;
            }
        }
        return null;
    }

    public synchronized void addInbox(InboxItem item) {
        inbox.add(item);
    }

    public synchronized boolean removeInbox(String handoffId) {
        return inbox.removeIf(item -> item.handoffId().equals(handoffId));
    }

    public synchronized int inboxSize() {
        return inbox.size();
    }

    /**
     * Records that {@code dedupeKey} was declined at {@code at}. FIFO-bounded at
     * {@value #MAX_DECLINED}.
     */
    public synchronized void tombstone(String dedupeKey, Instant at) {
        declined.remove(dedupeKey);
        declined.put(dedupeKey, at);
        Iterator<String> oldest = declined.keySet().iterator();
        while (declined.size() > MAX_DECLINED && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Whether {@code dedupeKey} was declined at or after {@code notBefore} (the
     * decline-memory horizon). An older tombstone no longer blocks.
     */
    public synchronized boolean isDeclinedSince(String dedupeKey, Instant notBefore) {
        Instant at = declined.get(dedupeKey);
        return at != null && !at.isBefore(notBefore);
    }

    /**
     * Where {@code dedupeKey} is already known: the status of the backlog task it
     * produced (looked up through {@code backlogStatus}, task id to status text),
     * {@code "PENDING_REVIEW"} for an inbox item, or {@code null} when unknown.
     */
    public synchronized String existingStatus(String dedupeKey, Function<String, String> backlogStatus) {
        for (Map.Entry<String, HandoffOrigin> entry : origins.entrySet()) {
            if (dedupeKey.equals(entry.getValue().dedupeKey())) {
                String status = backlogStatus.apply(entry.getKey());
                if (status != null) {
                    return status;
                }
            }
        }
        for (InboxItem item : inbox) {
            if (item.origin() != null && dedupeKey.equals(item.origin().dedupeKey())) {
                return "PENDING_REVIEW";
            }
        }
        return null;
    }

    /** Whether an inbox item holds this subject (case-insensitive). */
    public synchronized boolean inboxHasSubject(String subject) {
        for (InboxItem item : inbox) {
            if (item.subject() != null && item.subject().equalsIgnoreCase(subject)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops origins whose task is no longer on the backlog. {@code liveTaskIds} is
     * the backlog's current id set.
     *
     * @return how many origins were removed
     */
    public synchronized int pruneOrigins(Set<String> liveTaskIds) {
        int before = origins.size();
        origins.keySet().retainAll(liveTaskIds);
        return before - origins.size();
    }
}
