import { useSyncExternalStore } from "react";

/**
 * Hand-offs from a Workforce chat (T2.2): the person @mentions another agent or
 * a team, and the question goes there instead — a new chat, or a new team
 * session, with the question as its first message.
 *
 * **The hand-off is recorded in this browser only.** EDDI has no server-side
 * record of a user handing a question from one conversation to another. Its
 * one hand-off concept, cross-team handoff (`docs/team-handoff.md`), is an
 * agent filing a task into a standing team's backlog under two-sided author
 * consent — not a person moving their own question. So the client starts the
 * target conversation the ordinary way (the target agent or team sees a normal
 * first message, nothing about the origin) and keeps the link between the two
 * here, to draw a marker in each transcript. Another browser, another device,
 * or a cleared site data shows no marker; both conversations themselves are
 * complete on the server either way. Writing it into the transcript would need
 * a backend record (a follow-up), not a message the client fakes into the
 * conversation.
 *
 * Kept like the pinned responses (`workforce-pins.ts`): the key names the
 * signed-in principal, and sign-out removes every hand-off key. Each record
 * holds the start of the question, which is the person's own text and already
 * stored in the target conversation.
 */

export const WORKFORCE_HANDOFFS_PREFIX = "eddi-workforce-handoffs:";

/** Hand-offs kept per person; the oldest go first. */
export const MAX_HANDOFFS = 100;

/** The start of the question a marker shows. */
export const HANDOFF_QUESTION_CHARS = 140;

const ANONYMOUS_PRINCIPAL = "anonymous";

export interface WorkforceHandoff {
  id: string;
  createdAt: string;
  from: {
    agentId: string;
    agentName: string;
    conversationId: string;
    /** The turn on screen when the question was handed over (`lastStepIndex`). */
    afterStep: number | null;
  };
  to: {
    kind: "agent" | "team";
    id: string;
    name: string;
    /** The team's version, for its board link. */
    version?: number | null;
    /** The conversation the hand-off started, once known. */
    conversationId: string | null;
  };
  /** The start of the question. */
  question: string;
}

export function handoffsStorageKey(principal: string | null | undefined): string {
  const who = principal && principal.trim() ? principal.trim() : ANONYMOUS_PRINCIPAL;
  return `${WORKFORCE_HANDOFFS_PREFIX}${encodeURIComponent(who)}`;
}

function isHandoff(value: unknown): value is WorkforceHandoff {
  if (!value || typeof value !== "object") return false;
  const h = value as Partial<WorkforceHandoff>;
  return (
    typeof h.id === "string" &&
    typeof h.question === "string" &&
    !!h.from &&
    typeof h.from.conversationId === "string" &&
    typeof h.from.agentId === "string" &&
    !!h.to &&
    (h.to.kind === "agent" || h.to.kind === "team") &&
    typeof h.to.id === "string"
  );
}

const listeners = new Set<() => void>();
/** Parsed lists by key, so a snapshot is the same array until it changes. */
const cache = new Map<string, readonly WorkforceHandoff[]>();
const EMPTY: readonly WorkforceHandoff[] = [];

function emit(): void {
  for (const listener of [...listeners]) listener();
}

/** Every hand-off the person made in this browser, newest last. */
export function readHandoffs(principal: string | null | undefined, storage?: Storage): readonly WorkforceHandoff[] {
  const key = handoffsStorageKey(principal);
  const cached = cache.get(key);
  if (cached) return cached;
  let list: readonly WorkforceHandoff[];
  try {
    const raw = (storage ?? localStorage).getItem(key);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    list = Array.isArray(parsed) ? parsed.filter(isHandoff) : EMPTY;
  } catch {
    list = EMPTY;
  }
  cache.set(key, list);
  return list;
}

function write(principal: string | null | undefined, list: readonly WorkforceHandoff[], storage?: Storage): void {
  const key = handoffsStorageKey(principal);
  const kept = list.slice(-MAX_HANDOFFS);
  cache.set(key, kept);
  try {
    (storage ?? localStorage).setItem(key, JSON.stringify(kept));
  } catch {
    /* storage full or blocked: the marker lives until the page is left */
  }
  emit();
}

/** Record a hand-off; the question is cut to {@link HANDOFF_QUESTION_CHARS}. */
export function addHandoff(
  principal: string | null | undefined,
  handoff: Omit<WorkforceHandoff, "id" | "createdAt"> & { id?: string },
  storage?: Storage,
): WorkforceHandoff {
  const question = handoff.question.trim();
  const record: WorkforceHandoff = {
    ...handoff,
    id: handoff.id ?? newHandoffId(),
    createdAt: new Date().toISOString(),
    question: question.length > HANDOFF_QUESTION_CHARS ? `${question.slice(0, HANDOFF_QUESTION_CHARS - 1)}…` : question,
  };
  write(principal, [...readHandoffs(principal, storage), record], storage);
  return record;
}

/** The conversation a hand-off started is known now: its markers can link to it. */
export function completeHandoff(
  principal: string | null | undefined,
  id: string,
  conversationId: string,
  storage?: Storage,
): void {
  const list = readHandoffs(principal, storage);
  const index = list.findIndex((h) => h.id === id);
  if (index < 0 || list[index]!.to.conversationId === conversationId) return;
  const next = [...list];
  next[index] = { ...list[index]!, to: { ...list[index]!.to, conversationId } };
  write(principal, next, storage);
}

/** Remove every hand-off key (sign-out). Safe to call when storage is unavailable. */
export function clearWorkforceHandoffs(storage?: Storage): void {
  cache.clear();
  try {
    const store = storage ?? localStorage;
    const doomed: string[] = [];
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i);
      if (key && key.startsWith(WORKFORCE_HANDOFFS_PREFIX)) doomed.push(key);
    }
    doomed.forEach((key) => store.removeItem(key));
  } catch {
    /* storage blocked: nothing was kept */
  }
  emit();
}

function newHandoffId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `h-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  }
}

/**
 * Another tab or window changed (or cleared) a hand-off key. The `storage`
 * event never fires in the tab that made the change, so same-tab readers are
 * served by {@link emit}; this covers the other tabs, whose cached lists would
 * otherwise stay stale until reload — and whose next write would overwrite what
 * the first tab recorded. `key === null` means `localStorage.clear()`.
 */
function onStorage(event: StorageEvent): void {
  if (event.key === null) {
    cache.clear();
  } else if (event.key.startsWith(WORKFORCE_HANDOFFS_PREFIX)) {
    cache.delete(event.key);
  } else {
    return;
  }
  emit();
}

function subscribe(listener: () => void): () => void {
  if (listeners.size === 0 && typeof window !== "undefined") {
    window.addEventListener("storage", onStorage);
  }
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && typeof window !== "undefined") {
      window.removeEventListener("storage", onStorage);
    }
  };
}

/** The person's hand-offs, re-read when one is added or completed. */
export function useWorkforceHandoffs(principal: string | null | undefined): readonly WorkforceHandoff[] {
  return useSyncExternalStore(subscribe, () => readHandoffs(principal));
}
