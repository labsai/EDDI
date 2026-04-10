import { api } from "../api-client";

/* ─── Types ─── */

export interface GdprDeletionResult {
  userId: string;
  memoriesDeleted: number;
  conversationsDeleted: number;
  auditEntriesPseudonymized: number;
  logEntriesPseudonymized: number;
}

export interface UserDataExport {
  userId: string;
  memories: unknown[];
  conversations: unknown[];
  managedConversations: unknown[];
}

/* ─── API Functions ─── */

const BASE = "/admin/gdpr";

/** GDPR Art. 17 — Cascade erasure of all user data. */
export async function deleteUserData(
  userId: string,
): Promise<GdprDeletionResult> {
  return api.delete<GdprDeletionResult>(`${BASE}/${encodeURIComponent(userId)}`);
}

/** GDPR Art. 15/20 — Export all user data as JSON. */
export async function exportUserData(
  userId: string,
): Promise<UserDataExport> {
  return api.get<UserDataExport>(
    `${BASE}/${encodeURIComponent(userId)}/export`,
  );
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
