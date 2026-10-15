/**
 * Display formatting for a gated tool call's arguments.
 *
 * The backend sends `PendingToolCallView.arguments` as redacted JSON text, and a
 * generated tool nests its payload as JSON inside a JSON string —
 * `{"requestBody":"{\"agentName\":\"…\",\"systemPrompt\":\"…\"}"}` — so a plain
 * pretty-print still leaves the part a reviewer needs as one escaped line.
 * This unwraps those strings for READING.
 *
 * Display only. The decision payload, the amend flow and every JSON check keep
 * using the original `call.arguments`; nothing here is ever sent anywhere.
 */

/** Past this depth a nested JSON string is left as the string it is. */
const MAX_UNWRAP_DEPTH = 8;

/** `text` with insignificant whitespace (outside string literals) removed. Escapes inside strings are honoured. */
export function minifyJson(text: string): string {
  let out = "";
  let inString = false;
  let escaped = false;
  for (const ch of text) {
    if (inString) {
      out += ch;
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
    } else if (ch === '"') {
      inString = true;
      out += ch;
    } else if (ch !== " " && ch !== "\t" && ch !== "\n" && ch !== "\r") {
      out += ch;
    }
  }
  return out;
}

/**
 * Parse `text` only if re-serializing the result reproduces it exactly. JSON.parse
 * is lossy for the reviewer's purposes: integers past 2^53 round, `1.50` becomes
 * `1.5`, `1e3` becomes `1000`, a duplicate key silently keeps the last value, and
 * `\u00e9` / `\/` escapes change spelling. A formatted view that differs from what
 * will execute is worse than an ugly one, so any such text is "not parseable"
 * here and callers fall back to showing it as written.
 */
function parseLossless(text: string): unknown {
  try {
    const parsed: unknown = JSON.parse(text);
    return minifyJson(text) === JSON.stringify(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function unwrap(value: unknown, depth: number): unknown {
  if (typeof value === "string") {
    if (depth >= MAX_UNWRAP_DEPTH) return value;
    const trimmed = value.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      // Not JSON, or not losslessly so: keep the exact string.
      const parsed = parseLossless(trimmed);
      if (typeof parsed === "object" && parsed !== null) return unwrap(parsed, depth + 1);
    }
    return value;
  }
  if (Array.isArray(value)) return value.map((item) => unwrap(item, depth + 1));
  if (typeof value === "object" && value !== null) {
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) out[key] = unwrap(item, depth + 1);
    return out;
  }
  return value;
}

/**
 * `args` pretty-printed with nested JSON strings expanded, or `args` unchanged
 * when that cannot be done honestly: `truncated` text is a prefix of a document
 * and would not parse (or worse, would parse as something else), and text that
 * is not JSON has nothing to format. Never throws.
 */
export function formatToolArguments(args: string, truncated?: boolean): string {
  if (truncated) return args;
  try {
    const parsed = parseLossless(args);
    if (typeof parsed !== "object" || parsed === null) return args;
    return JSON.stringify(unwrap(parsed, 0), null, 2);
  } catch {
    return args;
  }
}
