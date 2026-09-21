import { api } from "../api-client";

/* ─── Types ─── */

/**
 * What the erasure cascade actually did.
 *
 * Every counter is named as EDDI names it. `logsPseudonymized` in particular:
 * this module called it `logEntriesPseudonymized`, a name the backend has never
 * sent, so the "Logs Pseudonymized" tile rendered `undefined` on every run.
 */
export interface GdprDeletionResult {
  userId: string;
  memoriesDeleted: number;
  conversationsDeleted: number;
  conversationMappingsDeleted: number;
  logsPseudonymized: number;
  auditEntriesPseudonymized: number;
  attachmentsDeleted: number;
  journalEntriesDeleted: number;
  checkpointsDeleted: number;
  groupConversationsDeleted: number;
  sharedArtifactsDeleted: number;
  schedulesDeleted: number;
  /**
   * Cascade steps that failed. Non-empty means some of the user's data may
   * still exist, and the erasure must NOT be reported to the data subject as
   * fulfilled.
   */
  failedSteps: string[];
  completedAt?: string;
  /** Derived by EDDI: `failedSteps` is empty. */
  complete: boolean;
}

/** One conversation in an export bundle. */
export interface UserDataExport {
  userId: string;
  exportedAt?: string;
  memories: unknown[];
  conversations: unknown[];
  managedConversations: unknown[];
  auditEntries: unknown[];
  attachments: unknown[];
  /** How many conversations exist, which `conversations.length` may undercut. */
  totalConversations: number;
  /** Whether the per-request conversation cap bit. */
  conversationsTruncated: boolean;
  /** Conversations the exporter could not load, and which are therefore absent. */
  failedConversationIds: string[];
  /**
   * Personal-data categories this exporter does not reach yet.
   *
   * Always non-empty today: EDDI erases group transcripts, shared artifacts,
   * schedules and HITL journal entries as this user's personal data, and
   * exports none of them. The two halves of the feature disagree about what the
   * user's data is, and only the deleting half is right.
   */
  omittedCategories: string[];
  /**
   * Whether this bundle covers everything EDDI holds on the user. False today
   * on every call, because `omittedCategories` is never empty.
   */
  complete: boolean;
}

/* ─── API Functions ─── */

const BASE = "/admin/gdpr";

/**
 * HTTP 207 Multi-Status, which erasure and export both answer when the result
 * is partial.
 *
 * 207 is inside the 2xx range, so `response.ok` is true and the plain
 * `api.delete` / `api.get` could not tell it from a 200. That is why both calls
 * below take the envelope: reporting a partial erasure as fulfilled, or an
 * incomplete bundle as a complete Article 15 answer, is the one failure mode
 * these endpoints exist to prevent — and the data subject is the one reader who
 * cannot check it.
 */
export const MULTI_STATUS = 207;

/**
 * GDPR Art. 17 — Cascade erasure of all user data.
 *
 * Trust `complete` over the status where they disagree: the status is derived
 * from it, and an older backend sends neither.
 */
export async function deleteUserData(
  userId: string,
): Promise<GdprDeletionResult> {
  const response = await api.deleteWithResponse<GdprDeletionResult>(
    `${BASE}/${encodeURIComponent(userId)}`,
  );
  return normaliseDeletion(response.data, response.status);
}

/** GDPR Art. 15/20 — Export all user data as JSON. */
export async function exportUserData(
  userId: string,
): Promise<UserDataExport> {
  const response = await api.getWithResponse<UserDataExport>(
    `${BASE}/${encodeURIComponent(userId)}/export`,
  );
  return normaliseExport(response.data, response.status);
}

/**
 * Fill in `complete` when the backend did not send it.
 *
 * An EDDI predating the 207 work sends neither `complete` nor `failedSteps`,
 * and answers 200. Defaulting the field to `false` there would tell a DPO that
 * a clean erasure had failed; defaulting to `true` on a 207 would undo the
 * whole point. So the status decides when the field is absent, and the field
 * decides when it is present.
 */
function normaliseDeletion(
  data: GdprDeletionResult,
  status: number,
): GdprDeletionResult {
  const failedSteps = data?.failedSteps ?? [];
  return {
    ...data,
    failedSteps,
    complete: data?.complete ?? (status !== MULTI_STATUS && failedSteps.length === 0),
  };
}

function normaliseExport(data: UserDataExport, status: number): UserDataExport {
  const omittedCategories = data?.omittedCategories ?? [];
  const failedConversationIds = data?.failedConversationIds ?? [];
  const truncated = data?.conversationsTruncated ?? false;
  return {
    ...data,
    omittedCategories,
    failedConversationIds,
    conversationsTruncated: truncated,
    complete:
      data?.complete ??
      (status !== MULTI_STATUS &&
        !truncated &&
        omittedCategories.length === 0 &&
        failedConversationIds.length === 0),
  };
}

/** GDPR Art. 18 — Restrict processing for a user. */
export async function restrictProcessing(userId: string): Promise<void> {
  return api.post(`${BASE}/${encodeURIComponent(userId)}/restrict`);
}

/** GDPR Art. 18 — Remove processing restriction. */
export async function unrestrictProcessing(userId: string): Promise<void> {
  return api.delete(`${BASE}/${encodeURIComponent(userId)}/restrict`);
}

/** GDPR Art. 18 — Check processing restriction status. */
export async function isProcessingRestricted(
  userId: string,
): Promise<boolean> {
  return api.get<boolean>(
    `${BASE}/${encodeURIComponent(userId)}/restrict`,
  );
}
