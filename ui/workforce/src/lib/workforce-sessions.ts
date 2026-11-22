import { parseConversationUri, type ConversationDescriptor } from "@eddi/app-kit/lib/api/conversations";
import type { GroupConversation } from "@eddi/app-kit/lib/api/groups";

/**
 * The Workforce sidebar's model (frontend unification plan, T1.1): one list of
 * the signed-in person's chats (1:1 agent conversations) and team sessions
 * (group conversations), grouped by date the way chat apps group them.
 *
 * Pure functions only — the hooks in `use-workforce-sessions.ts` fetch, this
 * shapes. Every date decision takes `now` so a test can pin it.
 */

export type SessionKind = "chat" | "team";

export interface WorkforceSession {
  kind: SessionKind;
  /** The conversation id (chat) or the group conversation id (team). */
  id: string;
  /** The stored title; `null` when there is none yet (see {@link sessionLabel}). */
  title: string | null;
  /** Agent (chat) or group (team) the session belongs to. */
  ownerId: string;
  /** The agent's or the team's name, for the fallback label. */
  ownerName: string;
  /** Last activity, epoch milliseconds. */
  lastActivity: number;
  /** Pinned to the top. Team sessions cannot be pinned (the backend has no field). */
  pinned: boolean;
  /** Where the session opens. */
  href: string;
}

/** The date buckets, in display order after the pinned section. */
export type DateBucket = "today" | "yesterday" | "previous7" | "previous30" | "older";

export interface SessionGroup {
  /** `pinned`, a {@link DateBucket}, or `month:YYYY-MM` for older sessions. */
  key: string;
  bucket: DateBucket | "pinned";
  /** First day of the month, for `older` groups (local time); `null` otherwise. */
  month: Date | null;
  sessions: WorkforceSession[];
}

/** How many recent conversations Home lists (T1.4). */
export const HOME_RECENT_COUNT = 5;

const DAY_MS = 86_400_000;

function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/**
 * Which bucket a session falls into: calendar days in local time, so a
 * session from 23:50 yesterday is "Yesterday" at 00:10 today.
 */
export function dateBucket(lastActivity: number, now: number): DateBucket {
  const today = startOfDay(now);
  if (lastActivity >= today) return "today";
  if (lastActivity >= today - DAY_MS) return "yesterday";
  if (lastActivity >= today - 7 * DAY_MS) return "previous7";
  if (lastActivity >= today - 30 * DAY_MS) return "previous30";
  return "older";
}

/**
 * Pinned first (most recent first), then the date buckets, then older
 * sessions one group per calendar month, newest month first. Empty groups are
 * left out.
 */
export function groupSessions(sessions: readonly WorkforceSession[], now: number): SessionGroup[] {
  const sorted = [...sessions].sort((a, b) => b.lastActivity - a.lastActivity);
  const pinned = sorted.filter((s) => s.pinned);
  const groups: SessionGroup[] = [];
  if (pinned.length) groups.push({ key: "pinned", bucket: "pinned", month: null, sessions: pinned });

  const byKey = new Map<string, SessionGroup>();
  for (const session of sorted) {
    if (session.pinned) continue;
    const bucket = dateBucket(session.lastActivity, now);
    let key: string = bucket;
    let month: Date | null = null;
    if (bucket === "older") {
      const d = new Date(session.lastActivity);
      month = new Date(d.getFullYear(), d.getMonth(), 1);
      key = `month:${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
    }
    let group = byKey.get(key);
    if (!group) {
      group = { key, bucket, month, sessions: [] };
      byKey.set(key, group);
      groups.push(group);
    }
    group.sessions.push(session);
  }
  return groups;
}

/**
 * What the sidebar shows for a session: its title, or — before the first turn
 * has given it one, or on a backend without titles — the agent's (team's)
 * name and the date.
 */
export function sessionLabel(
  session: Pick<WorkforceSession, "title" | "ownerName" | "ownerId" | "lastActivity">,
  formatDate: (ms: number) => string,
): string {
  const title = session.title?.trim();
  if (title) return title;
  return `${session.ownerName || session.ownerId} · ${formatDate(session.lastActivity)}`;
}

/** Where a chat opens: the agent and the conversation, both in the URL (T1.15). */
export function chatHref(agentId: string, conversationId: string): string {
  return `/workforce/chat?agentId=${encodeURIComponent(agentId)}&conversationId=${encodeURIComponent(conversationId)}`;
}

/**
 * A conversation descriptor as a sidebar chat. `null` for a descriptor the
 * sidebar cannot open (no agent recorded).
 */
export function chatSession(
  descriptor: ConversationDescriptor,
  agentNames: ReadonlyMap<string, string>,
): WorkforceSession | null {
  const id = parseConversationUri(descriptor.resource);
  if (!id || !descriptor.agentId) return null;
  return {
    kind: "chat",
    id,
    title: descriptor.title ?? null,
    ownerId: descriptor.agentId,
    ownerName: agentNames.get(descriptor.agentId) || descriptor.agentName || descriptor.name || descriptor.agentId,
    lastActivity: descriptor.lastModifiedOn || descriptor.createdOn || 0,
    pinned: descriptor.pinned === true,
    href: chatHref(descriptor.agentId, id),
  };
}

function epoch(value: unknown): number {
  if (typeof value === "number") return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  }
  return 0;
}

/** A group conversation as a sidebar team session; its question is its title. */
export function teamSession(conversation: GroupConversation, groupName: string): WorkforceSession {
  const raw = conversation as GroupConversation & { lastModified?: unknown; created?: unknown };
  return {
    kind: "team",
    id: conversation.id,
    title: conversation.originalQuestion?.trim() || null,
    ownerId: conversation.groupId,
    ownerName: groupName,
    lastActivity: epoch(raw.lastModified) || epoch(raw.created),
    pinned: false,
    href: `/workforce/${encodeURIComponent(conversation.groupId)}?conversation=${encodeURIComponent(conversation.id)}`,
  };
}

/**
 * The conversations members hold inside team sessions. Each member of a
 * discussion talks in a conversation of its own, owned by the person who
 * started the discussion — they are part of the team session, not chats of
 * their own, so the sidebar leaves them out of "my chats".
 */
export function memberConversationIds(conversations: readonly GroupConversation[]): Set<string> {
  const ids = new Set<string>();
  for (const gc of conversations) {
    for (const id of Object.values(gc.memberConversationIds ?? {})) {
      if (id) ids.add(id);
    }
  }
  return ids;
}
