import { api } from "../api-client";
import { seg } from "./path-segment";

// ─────────────────────────────────────────────────────────────────
// Team lessons: what a team's RETRO phase learned. Other teams can be shown
// them where both sides agree, and a scheduled, cost-capped job merges
// duplicates and retires lessons a later outcome contradicted, keeping what it
// removed. Off until the group's configuration carries a `lessonConfig`.
// ─────────────────────────────────────────────────────────────────

/** Backend `LessonConfig.MAX_GROUPS`. */
export const LESSON_MAX_GROUPS = 20;

/** Backend `LessonConsolidationConfig`: the scheduled merge/retire job. */
export interface LessonConsolidationConfig {
  llmProvider?: string | null;
  llmModel?: string | null;
  /** Dollars one run may spend; checked before the call against a worst-case estimate. */
  maxCostPerRun: number;
  minLessons: number;
  maxLessonsPerRun: number;
  outcomeLookbackDays: number;
  maxOutcomesPerRun: number;
}

/** Backend `LessonConfig`: the group's `lessonConfig`. */
export interface LessonConfig {
  /** Group ids allowed to read this team's lessons. */
  shareWith: string[];
  /** Group ids this team reads lessons from. */
  readFrom: string[];
  maxSharedLessons: number;
  consolidation?: LessonConsolidationConfig | null;
}

/** Backend `LessonService.LessonView`. */
export interface LessonView {
  key: string;
  text: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface SharedGroup {
  groupId: string;
  name: string;
  lessons: LessonView[];
}

/** Backend `LessonService.SharedLessons`. `notShared` names owners this team asked for that did not grant it. */
export interface SharedLessons {
  readerGroupId: string;
  groups: SharedGroup[];
  notShared: string[];
}

export type LessonHistoryAction = "MERGED" | "RETIRED";

/** Backend `LessonHistoryEntry`: a lesson consolidation removed from the live set. */
export interface LessonHistoryEntry {
  id: string;
  groupId: string;
  lessonKey: string;
  lessonValue: string;
  action: LessonHistoryAction;
  reason: string | null;
  replacedByKey: string | null;
  evidence: string[];
  runId: string;
  lessonCreatedAt: string | null;
  at: string;
}

export interface ConsolidationState {
  configured: boolean;
  hasModel: boolean;
  scheduled: boolean;
  cronExpression?: string;
  timeZone?: string;
  enabled?: boolean;
  nextFire?: string | null;
}

export interface ProposedAction {
  type: "MERGE" | "RETIRE";
  keys: string[];
  text: string | null;
  reason: string | null;
  evidence: string[];
  applied: boolean;
  /** Why it was refused or not applied. */
  note: string | null;
}

/** Backend `LessonConsolidationService.Result`. */
export interface ConsolidationResult {
  groupId: string;
  runId: string;
  status: "COMPLETED" | "SKIPPED" | "FAILED";
  message: string;
  dryRun: boolean;
  lessonsConsidered: number;
  outcomesConsidered: number;
  estimatedCost: number;
  actions: ProposedAction[];
}

const base = (groupId: string) => `/groupstore/groups/${seg(groupId)}/lessons`;

/** GET /groupstore/groups/{groupId}/lessons — the team's own lessons, newest first. */
export function listGroupLessons(groupId: string, limit?: number): Promise<LessonView[]> {
  return api.get<LessonView[]>(`${base(groupId)}${limit ? `?limit=${limit}` : ""}`);
}

/** GET …/lessons/shared — what other teams share with this one, and who did not. */
export function getSharedLessons(groupId: string): Promise<SharedLessons> {
  return api.get<SharedLessons>(`${base(groupId)}/shared`);
}

/** GET …/lessons/history — what consolidation removed, newest first. */
export function getLessonHistory(groupId: string, limit?: number): Promise<LessonHistoryEntry[]> {
  return api.get<LessonHistoryEntry[]>(`${base(groupId)}/history${limit ? `?limit=${limit}` : ""}`);
}

/** GET …/lessons/consolidation — whether the job is configured and scheduled. */
export function getConsolidationState(groupId: string): Promise<ConsolidationState> {
  return api.get<ConsolidationState>(`${base(groupId)}/consolidation`);
}

/**
 * PUT …/lessons/consolidation/schedule — schedule (or reschedule) the job.
 * Failures: 400 (cron or zone), 409 (no `lessonConfig.consolidation`).
 */
export function scheduleConsolidation(groupId: string, cronExpression: string, timeZone?: string): Promise<{ scheduleId: string; nextFire: string }> {
  return api.put(`${base(groupId)}/consolidation/schedule`, { cronExpression, timeZone });
}

/** DELETE …/lessons/consolidation/schedule — 204, or 404 when there is none. */
export function unscheduleConsolidation(groupId: string): Promise<void> {
  return api.delete(`${base(groupId)}/consolidation/schedule`);
}

/**
 * POST …/lessons/consolidation/run — run now. `dryRun` calls the model and
 * reports what it would merge or retire without changing anything (it still
 * spends, up to `maxCostPerRun`).
 */
export function runConsolidation(groupId: string, dryRun: boolean): Promise<ConsolidationResult> {
  return api.post<ConsolidationResult>(`${base(groupId)}/consolidation/run?dryRun=${dryRun}`, {});
}
