import type { Agent } from "@/lib/api/agents";

/**
 * Editing an agent document locally before it is saved.
 *
 * The agent detail page keeps one draft of the whole agent document; its
 * sections edit that draft and nothing reaches the backend until Save. Every
 * agent PUT creates a new version, so saving per field — as the page used to —
 * turned one sitting of edits into a dozen versions, and gave the page no way
 * to throw an edit away.
 */

/** Called by a section with the whole agent document as it would write it. */
export type AgentEdit = (next: Agent) => void;

/**
 * `base` with every top-level field that `next` changed relative to `rendered`
 * — the document the section built its edit from. Sections replace whole
 * top-level blocks (`capabilities`, `hitlConfig`, …), so a changed field is one
 * whose reference differs; a field `next` dropped is dropped here too.
 *
 * This is what lets an edit built from a slightly older render (a callback that
 * closed over the previous document) land without undoing the edit that came
 * just before it in another block.
 */
export function applyChangedFields(base: Agent, rendered: Agent, next: Agent): Agent {
  const result: Record<string, unknown> = { ...base };
  const nextFields = next as Record<string, unknown>;
  const renderedFields = rendered as Record<string, unknown>;
  const keys = new Set([...Object.keys(renderedFields), ...Object.keys(nextFields)]);
  for (const key of keys) {
    if (nextFields[key] === renderedFields[key]) continue;
    if (key in nextFields) result[key] = nextFields[key];
    else delete result[key];
  }
  return result as Agent;
}

/**
 * Whether a draft differs from the stored document. Compared as JSON with keys
 * sorted, so an `undefined` field counts as absent — the reading the PUT body
 * gets — and a block that was removed and put back is not "changed" merely
 * because it now sits at the end of the object.
 */
export function isAgentDraftDirty(draft: Agent | null, stored: Agent | undefined): boolean {
  if (draft === null || stored === undefined) return false;
  return canonicalJson(draft) !== canonicalJson(stored);
}

function canonicalJson(value: unknown): string {
  return JSON.stringify(value, (_key, v: unknown) =>
    v !== null && typeof v === "object" && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : v,
  );
}

/** The top-level fields of the agent document that the draft changes. */
export function changedAgentFields(draft: Agent | null, stored: Agent | undefined): Set<string> {
  const changed = new Set<string>();
  if (draft === null || stored === undefined) return changed;
  const d = draft as Record<string, unknown>;
  const s = stored as Record<string, unknown>;
  for (const key of new Set([...Object.keys(d), ...Object.keys(s)])) {
    if (canonicalJson(d[key]) !== canonicalJson(s[key])) changed.add(key);
  }
  return changed;
}

/** The pretty-printed document, for the review-changes diff. */
export function agentJson(agent: Agent | undefined | null): string | null {
  return agent ? JSON.stringify(agent, null, 2) : null;
}
