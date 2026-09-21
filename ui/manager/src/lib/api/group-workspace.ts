import { api } from "../api-client";
import type { SharedTaskList, TaskItem } from "./groups";

// ─────────────────────────────────────────────────────────────────
// I13 — standing teams: a persistent workspace (backlog + metrics +
// cadences) bolted onto a group, run unattended by a scheduler.
// ─────────────────────────────────────────────────────────────────

/** Backend `GroupWorkspace.MAX_BACKLOG_SIZE`. */
export const WORKSPACE_MAX_BACKLOG_SIZE = 200;
/** Backend `GroupWorkspace.DEFAULT_MAX_BACKLOG_TASKS_PER_RUN`, used when a cadence's own value is non-positive. */
export const WORKSPACE_DEFAULT_MAX_BACKLOG_TASKS_PER_RUN = 5;
/** Backend `RestGroupWorkspace.MAX_CADENCES_PER_WORKSPACE`. */
export const WORKSPACE_MAX_CADENCES = 20;
/** Backend `RestGroupWorkspace.MAX_INPUT_TEMPLATE_LENGTH`. */
export const WORKSPACE_MAX_INPUT_TEMPLATE_LENGTH = 4000;
/** Backend `SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH` — same cap the addBacklogTask endpoint enforces. */
export const WORKSPACE_MAX_TASK_SUBJECT_LENGTH = 200;
/** Backend `SharedTaskList.MAX_AGENT_TASK_DESCRIPTION_LENGTH`. */
export const WORKSPACE_MAX_TASK_DESCRIPTION_LENGTH = 4000;

/** Sentinel `runningDiscussionId` value meaning "no cadence run is currently in flight" — backend `GroupWorkspace.NO_RUNNING_DISCUSSION`. */
export const NO_RUNNING_DISCUSSION = "";

/**
 * A recurring, scheduled discussion that pulls from the team's own backlog
 * (I13). Its fire-time state (next/last fire, enabled) lives on a PAIRED
 * `ScheduleConfiguration` row in the SAME generic schedule store used by Dream
 * consolidation and plain cron triggers — discriminated by
 * `metadata.teamCadenceType === "team_cadence"` — never a bespoke schedule type.
 * That row is read-only observability for this UI; every mutation must go
 * through `addWorkspaceCadence`/`deleteWorkspaceCadence` below, never the
 * generic `/schedulestore/schedules` endpoints, or `GroupWorkspace.cadences` and
 * the schedule store fall out of sync.
 */
export interface Cadence {
  cadenceId: string;
  /** Id of the paired row in the generic `/schedulestore/schedules` collection. */
  scheduleRef: string;
  /** Qute template rendered over the pulled backlog tasks at fire time. `null` falls back to a plain task listing. */
  inputTemplate: string | null;
  /** Top-N backlog tasks (by priority, descending) pulled per fire. Non-positive falls back to the default (5). */
  maxBacklogTasksPerRun: number;
  /** Dollar ceiling for one fire's discussion. `null` defers entirely to the group's own `protocol.maxCostPerDiscussion` — when both are set, the tighter of the two wins. */
  maxCostPerRun: number | null;
  /** The principal every fire of this cadence runs the discussion as — fixed at cadence-creation time, not the identity of whoever is currently viewing the workspace. */
  createdBy: string;
}

/** Per-member reliability counters (I13) — RECORDING only; nothing in v1 routes, weights, or filters assignment on these numbers. */
export interface MemberStats {
  tasksVerified: number;
  tasksFailed: number;
}

/** Aggregate stats for a standing team's workspace (I13), updated after every cadence fire settles (success or failure). */
export interface WorkspaceMetrics {
  discussions: number;
  tasksVerified: number;
  totalCost: number;
  lastRunAt: string | null;
  perMemberStats: Record<string, MemberStats>;
}

/**
 * A group's standing-team workspace (I13): a persistent backlog + metrics +
 * cadence list, auto-created empty on first `GET` if the group has none yet.
 * `backlog` here is the FULL `SharedTaskList` shape (`{tasks, awardedBids}`) —
 * contrast with {@link getGroupWorkspaceBacklog}, which returns a bare array.
 */
export interface GroupWorkspace {
  id: string;
  schemaVersion: number;
  groupId: string;
  backlog: SharedTaskList;
  metrics: WorkspaceMetrics;
  cadences: Cadence[];
  /** Id of the discussion a cadence fire currently has claimed, or `""` (see {@link NO_RUNNING_DISCUSSION}) when idle. At most one cadence run in flight per workspace, even with multiple cadences. */
  runningDiscussionId: string;
  /** Backlog task ids the in-flight claim pulled — cleared when the run settles. */
  pulledTaskIds: string[];
  created: string;
  lastModified: string;
  revision: string;
}

/** Body of `addWorkspaceBacklogTask`. */
export interface AddBacklogTaskRequest {
  subject: string;
  description?: string;
  /** Higher runs earlier when a cadence pulls — NOT the "0 = highest" convention `TaskItem`'s general Javadoc describes elsewhere. */
  priority?: number;
}

/** Body of `addWorkspaceCadence`. */
export interface AddCadenceRequest {
  cronExpression: string;
  /** IANA zone id. Blank/absent defaults to `"UTC"`. */
  timeZone?: string;
  inputTemplate?: string;
  maxBacklogTasksPerRun?: number;
  maxCostPerRun?: number;
}

/**
 * Read a group's standing-team workspace, auto-creating an empty one if none
 * exists yet. Read-repairs first: if a previously claimed cadence discussion has
 * since finished (COMPLETED/FAILED/CANCELLED), its outcome is written back to
 * the backlog before the response is built — so this call can itself mutate
 * task statuses and metrics as a side effect of reading them.
 * GET /groupstore/groups/{groupId}/workspace
 */
export function getGroupWorkspace(groupId: string): Promise<GroupWorkspace> {
  return api.get<GroupWorkspace>(
    `/groupstore/groups/${encodeURIComponent(groupId)}/workspace`,
  );
}

/**
 * Read just the backlog task array (same read-repair as {@link getGroupWorkspace}).
 * NOTE the shape difference: this returns a bare `TaskItem[]`, not the
 * `{tasks, awardedBids}` envelope `workspace.backlog` carries.
 * GET /groupstore/groups/{groupId}/workspace/backlog
 */
export function getGroupWorkspaceBacklog(groupId: string): Promise<TaskItem[]> {
  return api.get<TaskItem[]>(
    `/groupstore/groups/${encodeURIComponent(groupId)}/workspace/backlog`,
  );
}

/**
 * File a new backlog task. Subject must be non-blank, unique (case-insensitive)
 * within the backlog, and the backlog must be under its 200-task cap.
 * POST /groupstore/groups/{groupId}/workspace/backlog
 * Failures: 400 (blank/oversized subject or description), 409 (backlog full,
 * duplicate subject, or lost a concurrent-write retry — the thrown ApiError's
 * `message` names which).
 *
 * NOTE: there is no companion delete/update endpoint for a single backlog task
 * (confirmed absent on both REST and MCP) — the only way a task leaves PENDING
 * today is via a cadence run pulling and completing it.
 */
export function addWorkspaceBacklogTask(
  groupId: string,
  request: AddBacklogTaskRequest,
): Promise<TaskItem> {
  return api.post<TaskItem>(
    `/groupstore/groups/${encodeURIComponent(groupId)}/workspace/backlog`,
    request,
  );
}

/**
 * Add a recurring cadence. Creates the paired `ScheduleConfiguration` row first;
 * if appending the `Cadence` to the workspace then fails, the just-created
 * schedule is deleted as compensation (server-side — nothing for a caller to
 * clean up either way).
 * POST /groupstore/groups/{groupId}/workspace/cadences
 * Failures: 400 (blank/unparseable cron, oversized inputTemplate), 409 (already
 * at the 20-cadence cap).
 */
export function addWorkspaceCadence(
  groupId: string,
  request: AddCadenceRequest,
): Promise<Cadence> {
  return api.post<Cadence>(
    `/groupstore/groups/${encodeURIComponent(groupId)}/workspace/cadences`,
    request,
  );
}

/**
 * Delete a cadence — removes its paired schedule row first (best-effort; a
 * schedule-delete failure does not block the cadence removal), then the
 * `Cadence` entry itself.
 * DELETE /groupstore/groups/{groupId}/workspace/cadences/{cadenceId}
 * 204 on success. Failures: 404 (no workspace, or no such cadence).
 */
export function deleteWorkspaceCadence(
  groupId: string,
  cadenceId: string,
): Promise<void> {
  return api.delete(
    `/groupstore/groups/${encodeURIComponent(groupId)}/workspace/cadences/${encodeURIComponent(cadenceId)}`,
  );
}
