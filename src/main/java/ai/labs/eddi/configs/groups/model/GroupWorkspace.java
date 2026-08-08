/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A group's standing workspace (I13): the persistent half of a team. A group
 * <em>conversation</em> is an episode; the workspace is what survives between
 * episodes — the backlog, the cadences that pull work from it, and the team's
 * running metrics.
 * <p>
 * One document per group config id, in its own collection — deliberately NOT
 * embedded in the versioned {@link AgentGroupConfiguration}: the workspace
 * mutates on every cadence run and backlog write, and versioning it alongside
 * the config would turn each of those into a config revision.
 * <p>
 * <b>Concurrency.</b> Cadence fires are cluster-wide (any pod's poller may
 * claim one), so "is a cadence discussion already running?" is decided by a
 * conditional store write on {@link #runningDiscussionId} — see
 * {@code IGroupWorkspaceStore#claimRun} — never by in-JVM locks. The idle value
 * is the empty string, not {@code null}, because the conditional write compares
 * a stored field against a concrete value.
 * <p>
 * <b>Per-member stats are reliability RECORDING only</b> (research-adopted
 * substrate): nothing routes or weights on them in v1, and that is a deliberate
 * scope cut, not an oversight.
 *
 * @author ginccc
 */
public class GroupWorkspace {

    /**
     * Document shape version for THIS collection (independent of
     * {@code GroupConversation}'s). Version 1 is the first that ever existed.
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Backlog ceiling. A backlog is a working set, not an archive — past this size,
     * adding tasks fails with an actionable error instead of growing unboundedly
     * (an LLM or a runaway integration can call add in a loop).
     */
    public static final int MAX_BACKLOG_SIZE = 200;

    /** Default number of backlog tasks one cadence run pulls. */
    public static final int DEFAULT_MAX_BACKLOG_TASKS_PER_RUN = 5;

    /** {@link #runningDiscussionId}'s idle value — see the class Javadoc. */
    public static final String NO_RUNNING_DISCUSSION = "";

    private String id;
    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private String groupId;
    /**
     * The team's persistent backlog. Reuses {@link SharedTaskList} whole —
     * statuses, dependency plumbing, caps — so a cadence run can hand pulled tasks
     * straight to the task-force machinery without a parallel task model.
     */
    private SharedTaskList backlog = new SharedTaskList();
    private WorkspaceMetrics metrics = new WorkspaceMetrics();
    private List<Cadence> cadences = new CopyOnWriteArrayList<>();
    /**
     * The cadence discussion currently in flight, or
     * {@link #NO_RUNNING_DISCUSSION}. Claimed by conditional write before a cadence
     * run starts; cleared by writeback once that discussion reaches a terminal
     * state.
     */
    private String runningDiscussionId = NO_RUNNING_DISCUSSION;
    /**
     * Backlog task ids the running discussion pulled — what writeback returns to
     * PENDING if the discussion fails, and matches outcomes against when it
     * completes.
     */
    private List<String> pulledTaskIds = new CopyOnWriteArrayList<>();
    private Instant created;
    private Instant lastModified;
    /**
     * Optimistic-concurrency stamp for read-modify-write surfaces (review finding:
     * two concurrent backlog adds could both pass the cap/duplicate checks against
     * their own snapshots and the later whole-document write dropped the earlier
     * task). Bumped by {@code IGroupWorkspaceStore.casRevision}; stored as a string
     * because the conditional-store primitive compares string field equality.
     * {@code null} on documents created before this field existed.
     */
    private String revision = "0";

    /**
     * One scheduled pull from the backlog.
     *
     * @param cadenceId
     *            stable id within this workspace
     * @param scheduleRef
     *            id of the {@code ScheduleConfiguration} driving this cadence —
     *            cadence execution rides {@code SchedulePollerService}'s
     *            cluster-aware claim/lease/retry machinery whole; the workspace
     *            never runs its own timer
     * @param inputTemplate
     *            optional Qute template rendered over the backlog summary to
     *            produce the discussion question; {@code null} uses a plain default
     * @param maxBacklogTasksPerRun
     *            how many executable tasks one fire pulls (default
     *            {@value #DEFAULT_MAX_BACKLOG_TASKS_PER_RUN})
     * @param maxCostPerRun
     *            dollar ceiling for one cadence discussion — dollar-primary per the
     *            Dream precedent; {@code null} falls back to the group's own
     *            {@code maxCostPerDiscussion}
     * @param createdBy
     *            principal that created the cadence — the identity its discussions
     *            run under (a cadence run must be attributable to whoever set it
     *            up, not to a synthetic scheduler user)
     */
    public record Cadence(String cadenceId, String scheduleRef, String inputTemplate, int maxBacklogTasksPerRun,
            Double maxCostPerRun, String createdBy) {

        public Cadence {
            if (maxBacklogTasksPerRun <= 0) {
                maxBacklogTasksPerRun = DEFAULT_MAX_BACKLOG_TASKS_PER_RUN;
            }
        }
    }

    /**
     * The team's running totals. A plain mutable POJO — writeback is the only
     * writer, and it runs under the workspace's single-writer claim.
     */
    public static class WorkspaceMetrics {

        private int discussions;
        private int tasksVerified;
        private double totalCost;
        private Instant lastRunAt;
        private Map<String, MemberStats> perMemberStats = new ConcurrentHashMap<>();

        public int getDiscussions() {
            return discussions;
        }

        public void setDiscussions(int discussions) {
            this.discussions = discussions;
        }

        public int getTasksVerified() {
            return tasksVerified;
        }

        public void setTasksVerified(int tasksVerified) {
            this.tasksVerified = tasksVerified;
        }

        public double getTotalCost() {
            return totalCost;
        }

        public void setTotalCost(double totalCost) {
            this.totalCost = totalCost;
        }

        public Instant getLastRunAt() {
            return lastRunAt;
        }

        public void setLastRunAt(Instant lastRunAt) {
            this.lastRunAt = lastRunAt;
        }

        public Map<String, MemberStats> getPerMemberStats() {
            return perMemberStats;
        }

        public void setPerMemberStats(Map<String, MemberStats> perMemberStats) {
            this.perMemberStats = perMemberStats != null
                    ? new ConcurrentHashMap<>(perMemberStats)
                    : new ConcurrentHashMap<>();
        }
    }

    /**
     * Reliability recording for one member — see the class Javadoc's scope note.
     */
    public static class MemberStats {

        private int tasksVerified;
        private int tasksFailed;

        public int getTasksVerified() {
            return tasksVerified;
        }

        public void setTasksVerified(int tasksVerified) {
            this.tasksVerified = tasksVerified;
        }

        public int getTasksFailed() {
            return tasksFailed;
        }

        public void setTasksFailed(int tasksFailed) {
            this.tasksFailed = tasksFailed;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public SharedTaskList getBacklog() {
        return backlog;
    }

    public void setBacklog(SharedTaskList backlog) {
        this.backlog = backlog != null ? backlog : new SharedTaskList();
    }

    public WorkspaceMetrics getMetrics() {
        return metrics;
    }

    public void setMetrics(WorkspaceMetrics metrics) {
        this.metrics = metrics != null ? metrics : new WorkspaceMetrics();
    }

    /**
     * Read-only view — mutation goes through {@link #addCadence} and
     * {@link #removeCadence}, so the schedule references cannot be edited behind
     * the workspace's back.
     */
    public List<Cadence> getCadences() {
        return Collections.unmodifiableList(cadences);
    }

    public void addCadence(Cadence cadence) {
        if (cadence != null) {
            cadences.add(cadence);
        }
    }

    /** @return {@code true} if a cadence with that id existed and was removed */
    public boolean removeCadence(String cadenceId) {
        return cadences.removeIf(c -> c.cadenceId().equals(cadenceId));
    }

    public void setCadences(List<Cadence> cadences) {
        this.cadences = cadences != null ? new CopyOnWriteArrayList<>(cadences) : new CopyOnWriteArrayList<>();
    }

    public String getRunningDiscussionId() {
        return runningDiscussionId;
    }

    public void setRunningDiscussionId(String runningDiscussionId) {
        this.runningDiscussionId = runningDiscussionId != null ? runningDiscussionId : NO_RUNNING_DISCUSSION;
    }

    public List<String> getPulledTaskIds() {
        return pulledTaskIds;
    }

    public void setPulledTaskIds(List<String> pulledTaskIds) {
        this.pulledTaskIds = pulledTaskIds != null ? new CopyOnWriteArrayList<>(pulledTaskIds) : new CopyOnWriteArrayList<>();
    }

    public Instant getCreated() {
        return created;
    }

    public void setCreated(Instant created) {
        this.created = created;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }
}
