/**
 * Parsing JSON that EDDI's secret redaction has already been through.
 *
 * `SecretRedactionFilter` (EDDI: `secrets/sanitize/SecretRedactionFilter.java`)
 * runs over the raw request TEXT, not over a parsed document, and its last rule
 *
 *     (api[_-]?key|token|secret|password|authorization)(?:\\*["'])?\s*[=:]\s*(?:\\*["'])?[^'"\\\s,;}{\]]{8,}
 *       → $1=<REDACTED>
 *
 * matches the key's closing quote, the colon AND the value's opening quote, then
 * replaces the lot. So a perfectly ordinary body comes back malformed:
 *
 *     {"modelName":"x","apiKey":"sk-ant-…"}  →  {"modelName":"x","apiKey=<REDACTED>"}
 *
 * — a bare string where a key/value pair was. The `sk-…` and `Bearer …` rules
 * replace only the value and leave the document valid; this one does not, and it
 * runs last, so it re-mangles what those already redacted.
 *
 * Two client-side readers parse that body, and BOTH failed silently on it:
 *
 * - the approval diff, which fell back to comparing raw text and reported the
 *   whole stored document as deleted;
 * - `detectEscalationFlags`, whose capability-grant checks all sit behind a
 *   `JSON.parse` — so a request that embedded a credential AND granted dynamic
 *   agent creation warned about the credential only. "No warning" reads as "no
 *   capability grant", which is the exact false negative that file exists to
 *   prevent.
 *
 * Repairing here rather than at either call site keeps them agreeing about what
 * a redacted body means — the same reason `RequestRedactor` holds the backend's
 * three redaction sites together in one class.
 */

/**
 * A mangled field, matched up to the marker and no further.
 *
 * `([{,]\s*)` anchors the match in KEY position, so a *value* that legitimately
 * reads `"apiKey=<REDACTED>"` (the filter's correct output for a credential
 * embedded in a string) is never rewritten. `[^"\\]` cannot cross a quote, which
 * also bounds the backtracking — this runs on untrusted request bodies, and the
 * backend went possessive on its own quantifiers over the same concern.
 *
 * Deliberately stops at the marker rather than consuming what follows: the text
 * between the marker and the field's closing quote is ambiguous (see
 * {@link REMNANT_CHOICES}), and a pattern greedy enough to swallow it also
 * swallows the next field's opening quote, hiding that field from the scan.
 */
const REDACTED_FIELD =
  /([{,]\s*)"([^"\\]*?(?:api[_-]?key|token|secret|password|authorization))=<REDACTED>/gi;

/** Cheap pre-check: the mangled shape always contains this. */
const MANGLED_MARKER = "=<REDACTED>";

/**
 * What to do with the text between the marker and the field's closing quote.
 *
 * The filter's value class excludes `,`, whitespace, `;`, `{`, `}` and `]`, so
 * it stops at the first one inside the secret and leaves the rest behind. Two
 * readings, and they are not distinguishable locally:
 *
 * - **drop** — the field was a string whose secret contained such a character,
 *   so the leftovers up to the closing quote are secret material to discard:
 *   `{"password=<REDACTED>,rest","n":1}` → `{"password":"<REDACTED>","n":1}`
 * - **keep** — the field had a non-string value, so nothing was left behind and
 *   the very next quote belongs to the FOLLOWING key:
 *   `{"token=<REDACTED>,"n":1}` → `{"token":"<REDACTED>","n":1}`
 *
 * Each field is decided independently — a body can contain one of each — and
 * `JSON.parse` is the arbiter, so a wrong guess is discarded rather than shown.
 */
const REMNANT_CHOICES = ["drop", "keep"] as const;

/**
 * Cap on the exhaustive per-field search, which costs 2^n parses of the whole
 * body. The uniform pair above already answers any number of fields that AGREE,
 * so this only bounds the mixed case — and a document mixing more than six
 * disagreeing credential fields is not a scenario, it is an attack surface.
 * Six keeps the worst case at 64 parses; ten would have been 1024.
 */
const MAX_SEARCHED_FIELDS = 6;

export type ParseResult = { ok: true; value: unknown } | { ok: false };

/**
 * `JSON.parse`, retried over repaired variants of the body if it throws.
 *
 * The repair is a FALLBACK, never a pre-pass: a body that already parses is
 * returned untouched, so a document carrying the marker legitimately inside a
 * string — `"${vault:<REDACTED>}"`, or an escaped JSON body nested in a field —
 * is never rewritten. Every candidate is validated by parsing it, so a repair
 * that does not produce valid JSON leaves the caller exactly the failure it had
 * before.
 */
export function parseRedactedJson(content: string): ParseResult {
  const direct = tryParse(content);
  if (direct.ok) return direct;
  if (!content.includes(MANGLED_MARKER)) return { ok: false };

  const fields = [...content.matchAll(REDACTED_FIELD)];
  if (fields.length === 0) return { ok: false };

  for (const choices of candidateChoices(fields.length)) {
    const repaired = tryParse(rebuild(content, fields, choices));
    if (repaired.ok) return repaired;
  }
  return { ok: false };
}

/**
 * Per-field choices to try, uniform ones first.
 *
 * All-drop covers every string-valued field, all-keep every non-string one, and
 * those two answer any body whose fields agree with each other — which is all of
 * them bar a genuine mix. The mixed combinations follow.
 */
function* candidateChoices(fieldCount: number): Generator<("drop" | "keep")[]> {
  for (const choice of REMNANT_CHOICES) {
    yield Array.from({ length: fieldCount }, () => choice);
  }
  if (fieldCount < 2 || fieldCount > MAX_SEARCHED_FIELDS) return;

  // Bit i decides field i. 0 and the all-ones mask are the uniform pair above.
  for (let mask = 1; mask < (1 << fieldCount) - 1; mask++) {
    yield Array.from({ length: fieldCount }, (_, i) => (mask & (1 << i) ? "keep" : "drop"));
  }
}

/** Re-quote every matched field, resolving each one's remnant as chosen. */
function rebuild(
  content: string,
  fields: RegExpExecArray[],
  choices: readonly ("drop" | "keep")[],
): string {
  let out = "";
  let cursor = 0;
  fields.forEach((field, i) => {
    const start = field.index;
    // A field the previous one's dropped remnant already swallowed.
    if (start < cursor) return;

    out += content.slice(cursor, start) + `${field[1]}"${field[2]}":"<REDACTED>"`;
    cursor = start + field[0].length;

    if (choices[i] === "drop") {
      const closing = endOfStringLiteral(content, cursor);
      if (closing >= 0) cursor = closing;
    }
  });
  return out + content.slice(cursor);
}

/** Index just past the next unescaped `"`, or -1 if the string never closes. */
function endOfStringLiteral(content: string, from: number): number {
  for (let i = from; i < content.length; i++) {
    if (content[i] === "\\") i++;
    else if (content[i] === '"') return i + 1;
  }
  return -1;
}

function tryParse(content: string): ParseResult {
  try {
    return { ok: true, value: JSON.parse(content) };
  } catch {
    return { ok: false };
  }
}
