import { ApiClientError } from "./api-client";

/** One list of things a refused delete says still depend on the resource. */
export interface ConflictList {
  /** The body field the list came from, as the server named it. */
  field: string;
  /** One readable line per entry. */
  items: string[];
}

/** What a 409 answer says: its sentence and whatever lists it carries. */
export interface ConflictDetails {
  message: string;
  lists: ConflictList[];
}

/** The fields, in order, that make an object entry readable on its own line. */
const LABEL_FIELDS = ["name", "title", "label", "displayName", "id", "agentId", "conversationId", "resource", "uri"];

const MAX_ITEMS_PER_LIST = 25;

function describeEntry(entry: unknown): string | null {
  if (typeof entry === "string") return entry.trim() === "" ? null : entry;
  if (typeof entry === "number" || typeof entry === "boolean") return String(entry);
  if (entry && typeof entry === "object") {
    const record = entry as Record<string, unknown>;
    const parts: string[] = [];
    for (const field of LABEL_FIELDS) {
      const v = record[field];
      if (typeof v === "string" && v.trim() !== "" && !parts.includes(v)) parts.push(v);
      if (parts.length === 2) break;
    }
    if (parts.length > 0) return parts.join(" · ");
    const json = JSON.stringify(entry);
    return json.length > 120 ? `${json.slice(0, 117)}…` : json;
  }
  return null;
}

/**
 * Reads a 409 for what it says, without assuming field names: the message, and
 * every top-level array in the body (and arrays one object down) as a list of
 * readable lines. `null` when the error is not a 409.
 *
 * Used for deletes the server refuses because something still uses the resource
 * (active conversations, workflows or configurations that reference it).
 */
export function conflictDetails(error: unknown): ConflictDetails | null {
  if (!(error instanceof ApiClientError) || error.status !== 409) return null;
  const lists: ConflictList[] = [];
  const body = error.body;
  if (body && typeof body === "object" && !Array.isArray(body)) {
    for (const [field, value] of Object.entries(body as Record<string, unknown>)) {
      if (Array.isArray(value)) {
        pushList(lists, field, value);
      } else if (value && typeof value === "object" && !Array.isArray(value)) {
        for (const [inner, innerValue] of Object.entries(value as Record<string, unknown>)) {
          if (Array.isArray(innerValue)) pushList(lists, `${field}.${inner}`, innerValue);
        }
      }
    }
  } else if (Array.isArray(body)) {
    pushList(lists, "", body);
  }
  return { message: error.message, lists };
}

function pushList(lists: ConflictList[], field: string, entries: unknown[]) {
  const items = entries.map(describeEntry).filter((x): x is string => x !== null);
  if (items.length === 0) return;
  lists.push({
    field,
    items:
      items.length > MAX_ITEMS_PER_LIST
        ? [...items.slice(0, MAX_ITEMS_PER_LIST), `… +${items.length - MAX_ITEMS_PER_LIST}`]
        : items,
  });
}
