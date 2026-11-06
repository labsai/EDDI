/**
 * Readable text for tool calls, shared by every chat surface (the Manager chat,
 * the chat drawer, the Platform Operator, group transcripts) and the approval UI.
 *
 * The engine identifies a tool by the name the model invoked: a built-in slug
 * (`webSearch`), an httpcall name, an OpenAPI `operationId` for a generated tool
 * (`getAgentDescriptors`) or an MCP tool name (`add_issue_comment`). Those are
 * identifiers, not text a person should have to read. When the designer set
 * `toolDisplay` on the LLM task, the SSE `tool_call` event carries a label
 * (`displayName`) and up to three allow-listed argument values (`display`) —
 * that wins. Otherwise the identifier is humanised here.
 *
 * Display only: decisions, filters and comparisons keep using the raw name.
 */

/** A name with whitespace is already text (a designer label) and is left alone. */
const IDENTIFIER = /^[\p{L}\p{N}_.:/\-$]+$/u;

/**
 * True for a tool identifier as the model invoked it; false for display text
 * such as a `toolDisplay` label ("Creating an action: Renew the contract"),
 * which reads as a sentence on its own and must not be wrapped in "Using …".
 */
export function isToolIdentifier(name: string | null | undefined): boolean {
  const raw = (name ?? "").trim();
  return !!raw && IDENTIFIER.test(raw);
}

/** A token written in capitals (`HTTP`, `URL`, `A2A`) stays as it is. */
function isAcronym(token: string): boolean {
  return token.length > 1 && token === token.toUpperCase() && /\p{Lu}/u.test(token);
}

/**
 * `getAgentDescriptors` → "Get agent descriptors", `add_issue_comment` → "Add
 * issue comment", `fetchHTTPStatus` → "Fetch HTTP status", `rag.search` → "Rag
 * search". Text that is not identifier-shaped (it has spaces) is returned
 * trimmed but otherwise as written, so a designer's label is never re-cased.
 * An empty or unusable name comes back unchanged.
 */
export function humanizeToolName(name: string | null | undefined): string {
  const raw = (name ?? "").trim();
  if (!raw || !IDENTIFIER.test(raw)) return raw;
  const tokens = raw
    // camelCase and PascalCase boundaries, keeping acronyms together:
    // "fetchHTTPStatus" → "fetch HTTP Status", "v2Agents" → "v2 Agents".
    .replace(/([\p{Ll}\p{N}])(\p{Lu})/gu, "$1 $2")
    .replace(/(\p{Lu}+)(\p{Lu}\p{Ll})/gu, "$1 $2")
    .split(/[\s_.:/\-$]+/u)
    .filter(Boolean);
  if (tokens.length === 0) return raw;
  const words = tokens.map((token, i) => {
    if (isAcronym(token)) return token;
    const lower = token.toLowerCase();
    return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
  });
  return words.join(" ");
}

/** The parts of a live SSE `tool_call` event a client may show. */
export interface ToolCallDisplay {
  /** The name the model invoked — the identity, for comparisons. */
  tool: string;
  /** The designer's `toolDisplay` label, when configured. */
  displayName?: string;
  /** Allow-listed, redacted, capped argument values, when configured. */
  display?: Record<string, string>;
}

/**
 * Parses a `tool_call` SSE payload — `{tool, callId, displayName?, display?}`
 * (`RestAgentEngineStreaming.ToolCallEvent`). Null for anything without a
 * usable `tool`, so a malformed frame leaves the status line as it was.
 */
export function parseToolCallEvent(data: string): ToolCallDisplay | null {
  if (!data?.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(data);
    if (!parsed || typeof parsed !== "object") return null;
    const record = parsed as Record<string, unknown>;
    if (typeof record.tool !== "string" || !record.tool.trim()) return null;
    const out: ToolCallDisplay = { tool: record.tool };
    if (typeof record.displayName === "string" && record.displayName.trim()) {
      out.displayName = record.displayName.trim();
    }
    if (record.display && typeof record.display === "object" && !Array.isArray(record.display)) {
      const fields: Record<string, string> = {};
      for (const [key, value] of Object.entries(record.display as Record<string, unknown>)) {
        if (typeof value === "string" && value.trim()) fields[key] = value;
      }
      if (Object.keys(fields).length) out.display = fields;
    }
    return out;
  } catch {
    return null;
  }
}

/**
 * What a live status line records for a tool call: the designer's phrase
 * ("Creating an action: Renew the contract") when `toolDisplay` configured one,
 * otherwise the tool identifier unchanged — the renderer humanises an
 * identifier and wraps it ("Using Get agent descriptors…"), while a phrase is
 * shown as written (see `isToolIdentifier`).
 */
export function toolCallLabel(call: ToolCallDisplay): string {
  const values = call.display ? Object.values(call.display) : [];
  if (!call.displayName && values.length === 0) return call.tool;
  const label = call.displayName || humanizeToolName(call.tool) || call.tool;
  return values.length ? `${label}: ${values.join(", ")}` : label;
}

/** A field of a tool call's arguments, ready to render as a label/value row. */
export interface ToolArgumentField {
  /** The argument name as sent — kept for keys and the raw view. */
  key: string;
  /** The humanised argument name. */
  label: string;
  /** A scalar shown as text, or nested fields for an object. */
  value: string | ToolArgumentField[];
  /** True when `value` is JSON text (an array or a deeply nested object). */
  json?: boolean;
}

/** Past this depth an object is shown as JSON text rather than nested rows. */
const MAX_FIELD_DEPTH = 3;

function scalarText(value: unknown): string {
  if (typeof value === "string") return value;
  return JSON.stringify(value);
}

function toFields(obj: Record<string, unknown>, depth: number): ToolArgumentField[] {
  return Object.entries(obj).map(([key, value]) => {
    const label = humanizeToolName(key) || key;
    if (value !== null && typeof value === "object") {
      if (Array.isArray(value)) {
        const allScalar = value.every((item) => item === null || typeof item !== "object");
        return allScalar
          ? { key, label, value: value.map(scalarText).join(", ") }
          : { key, label, value: JSON.stringify(value, null, 2), json: true };
      }
      if (depth + 1 >= MAX_FIELD_DEPTH) {
        return { key, label, value: JSON.stringify(value, null, 2), json: true };
      }
      return { key, label, value: toFields(value as Record<string, unknown>, depth + 1) };
    }
    return { key, label, value: scalarText(value) };
  });
}

/**
 * The arguments of a tool call as label/value rows, or null when they cannot be
 * shown that way honestly — truncated text, text that is not a JSON object, or
 * JSON that would not read back exactly as sent (see `parseToolArgumentsForDisplay`).
 * Callers then show the text as written.
 */
export function toolArgumentFields(
  parsed: unknown,
): ToolArgumentField[] | null {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  const fields = toFields(parsed as Record<string, unknown>, 0);
  return fields.length ? fields : null;
}
