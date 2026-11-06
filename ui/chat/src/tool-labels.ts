/* ──────────────────────────────────────────────
   EDDI Chat — readable tool names
   The engine names a tool by the identifier the model invoked: a built-in
   slug (`webSearch`), an httpcall name, an OpenAPI operationId
   (`getAgentDescriptors`) or an MCP tool (`add_issue_comment`). The widget
   shows those in words. A designer's `toolDisplay` label (EDDI 6.7, carried
   on the `tool_call` event as `displayName`) is text already and wins.
   Mirrors ui/manager/src/lib/tool-labels.ts.
   ────────────────────────────────────────────── */

/** A name with whitespace is already text (a designer label) and is left alone. */
const IDENTIFIER = /^[\p{L}\p{N}_.:/\-$]+$/u;

/** True for a tool identifier; false for display text such as a `toolDisplay` label. */
export function isToolIdentifier(name: string | null | undefined): boolean {
  const raw = (name ?? "").trim();
  return !!raw && IDENTIFIER.test(raw);
}

/** A token written in capitals (`HTTP`, `URL`) stays as it is. */
function isAcronym(token: string): boolean {
  return token.length > 1 && token === token.toUpperCase() && /\p{Lu}/u.test(token);
}

/**
 * `getAgentDescriptors` → "Get agent descriptors", `add_issue_comment` → "Add
 * issue comment", `fetchHTTPStatus` → "Fetch HTTP status". Text with spaces is
 * returned trimmed but otherwise as written.
 */
export function humanizeToolName(name: string | null | undefined): string {
  const raw = (name ?? "").trim();
  if (!raw || !IDENTIFIER.test(raw)) return raw;
  const tokens = raw
    .replace(/([\p{Ll}\p{N}])(\p{Lu})/gu, "$1 $2")
    .replace(/(\p{Lu}+)(\p{Lu}\p{Ll})/gu, "$1 $2")
    .split(/[\s_.:/\-$]+/u)
    .filter(Boolean);
  if (tokens.length === 0) return raw;
  return tokens
    .map((token, i) => {
      if (isAcronym(token)) return token;
      const lower = token.toLowerCase();
      return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(" ");
}
