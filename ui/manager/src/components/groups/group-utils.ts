// ─── Shared Types ────────────────────────────────────────────────

/** Structured item from moderator's JSON or emoji-formatted output (plans, verifications, etc.) */
export interface StructuredItem {
  subject: string;
  description?: string;
  assignedTo?: string;
  priority?: number;
  passed?: boolean;
  feedback?: string;
}

// ─── Markdown Normalizer ─────────────────────────────────────────

/**
 * Regions whose contents must never be rewritten by the normalizer.
 *
 * The normalizer repairs prose an LLM formatted badly. Applied to code, URLs or
 * JSON it does the opposite — it corrupts them. `apiKey` is not a missing space,
 * and `https://host/GetStarted` is not a sentence. Ordered so the widest
 * construct is captured first (a fence may legally contain backticks).
 */
const PROTECTED_PATTERNS: readonly RegExp[] = [
  // Closed constructs first, so a complete fence wins over the open-ended
  // fallbacks below it.
  /```[\s\S]*?```/g, // fenced code block
  /~~~[\s\S]*?~~~/g, // fenced code block (tilde form)

  // UNTERMINATED fence, to end of input. Essential, not defensive: these
  // functions re-run on every render while tokens are still arriving, so for the
  // whole time a code block is streaming in there is no closing fence yet.
  // Without this the prose rules rewrite the code the user is watching and it
  // visibly snaps back once the fence lands — e.g. `.btn { color:#fff }` became
  // `.btn { color:` + blank line + `#fff }` via the heading rule.
  /```[\s\S]*$/g,
  /~~~[\s\S]*$/g,

  /`[^`\n]*`/g, // inline code span
  /`[^`\n]*$/gm, // unterminated inline code span, to end of line (streaming)

  // Indented code block (4 spaces or a tab). A standard CommonMark form that
  // still reached the prose rules even when complete.
  /^(?:[ ]{4}|\t).*(?:\n(?:[ ]{4}|\t).*)*/gm,

  /\]\([^)\s]*(?:\s+"[^"]*")?\)/g, // markdown link / image destination
  /<[a-zA-Z][a-zA-Z0-9+.-]*:[^>\s]*>/g, // autolink
  /\bhttps?:\/\/[^\s)]+/g, // bare URL
];

/**
 * Sentinel wrapping a stash index, using a Unicode Private Use Area character.
 * PUA code points carry no meaning in real text, so the placeholder can never
 * collide with agent output — unlike a space- or bracket-delimited token, which
 * would swallow ordinary numbers ("in 5 minutes").
 */
const SENTINEL = String.fromCharCode(0xe000);
const PLACEHOLDER = new RegExp(SENTINEL + "(\\d+)" + SENTINEL, "g");

/**
 * Replace every protected region with an opaque placeholder, returning the
 * masked text plus a `restore` that puts the originals back.
 *
 * A later pattern can swallow an earlier placeholder (a bare URL followed
 * directly by an inline code span), so `restore` loops until no placeholder
 * remains — `String.replace` does not rescan text it just inserted.
 */
function maskProtectedRegions(text: string): {
  masked: string;
  restore: (s: string) => string;
} {
  const stash: string[] = [];
  // Strip any sentinel already in the input FIRST. Agent output is
  // attacker-influenceable via prompt injection, and a literal
  // U+E000<digits>U+E000 would otherwise be indistinguishable from a real
  // placeholder — restore would substitute an unrelated stashed region into the
  // message (duplicating it) or, for an out-of-range index, delete the text.
  // U+E000 is Private Use Area and carries no meaning in real content, so
  // dropping it loses nothing.
  let masked = text.split(SENTINEL).join("");
  for (const pattern of PROTECTED_PATTERNS) {
    masked = masked.replace(pattern, (match) => {
      stash.push(match);
      return SENTINEL + (stash.length - 1) + SENTINEL;
    });
  }
  const restore = (s: string): string => {
    let out = s;
    for (let i = 0; i <= PROTECTED_PATTERNS.length && out.includes(SENTINEL); i++) {
      out = out.replace(PLACEHOLDER, (_m, idx: string) => stash[Number(idx)] ?? "");
    }
    return out;
  };
  return { masked, restore };
}

/**
 * Format and normalize markdown text to ensure proper rendering across all UI surfaces.
 *
 * Code spans, fenced blocks, link destinations and URLs are masked out first, so
 * every rule below applies to prose only.
 *
 * 1. Fixes ATX headings missing space or glued to preceding words (e.g. `word## Header` -> `word\n\n## Header`)
 * 2. Fixes trailing hyphens/dashes attached to opening bold markers (e.g. `Das- **Logo` -> `Das - **Logo`)
 * 3. Removes illegal whitespace INSIDE bold delimiters so CommonMark parsers recognize bold text:
 *    (e.g. `** von der Strategie **` -> `**von der Strategie**`, `** warum**` -> `**warum**`)
 * 4. Ensures space BEFORE opening `**` if glued to preceding text (e.g. `word**bold**` -> `word **bold**`)
 * 5. Ensures space AFTER closing `**` if glued to following word (e.g. `**bold**word` -> `**bold** word`)
 * 6. Fixes list hyphens glued to colons (e.g. `bedeutet:- Zuerst` -> `bedeutet: - Zuerst`)
 * 7. Fixes missing spaces after punctuation before capitalized words (e.g. `mich,Sie` -> `mich, Sie`)
 *
 * There is deliberately NO lowercase->uppercase word splitter. It cannot tell a
 * dropped space (`SieSind`) from an identifier (`apiKey`, `PostgreSQL`,
 * `conversationId`), and in an agent-platform console the identifiers dominate.
 */
export function formatMarkdownText(text: string): string {
  if (!text) return "";

  const { masked, restore } = maskProtectedRegions(text);
  let formatted = masked;

  // 1. Fix ATX headings missing space after # (e.g. "#Header" -> "# Header", "###Title" -> "### Title")
  formatted = formatted.replace(/^(#{1,6})([^\s#])/gm, "$1 $2");

  // 2. Fix ATX headings glued directly to preceding text on same line (e.g. "Schließlich## 🌐" -> "Schließlich\n\n## 🌐")
  formatted = formatted.replace(/([^\s\n#])(#{1,6}\s)/g, "$1\n\n$2");
  formatted = formatted.replace(/([^\s\n#])(#{1,6}[^\s#])/g, "$1\n\n$2");

  // 3. Fix list/dash attached to colons (e.g. "bedeutet:- Zuerst" -> "bedeutet: - Zuerst")
  formatted = formatted.replace(/([a-zA-Z0-9äöüßÄÖÜ]):-\s*/g, "$1: - ");

  // 4. Fix trailing hyphens/dashes attached to opening bold markers (e.g. "Das- **Logo" -> "Das - **Logo")
  formatted = formatted.replace(/([a-zA-Z0-9äöüßÄÖÜ])-\s*\*\*/g, "$1 - **");

  // 5. NORMALIZE WHITESPACE INSIDE DOUBLE ASTERISKS (**word ** -> **word**, ** word** -> **word**)
  // CommonMark explicitly disallows leading whitespace after opening ** or trailing whitespace before closing **.
  // Use [\s\u00a0] to also match non-breaking spaces and other Unicode whitespace.
  // Pass A: both sides have whitespace (** word **)
  formatted = formatted.replace(/\*\*[\s\u00a0]+([^*]+?)[\s\u00a0]+\*\*/g, (_m, inner: string) => `**${inner.trim()}**`);
  // Pass B: leading whitespace only (** word**)
  formatted = formatted.replace(/\*\*[\s\u00a0]+([^*]+?)\*\*/g, (_m, inner: string) => `**${inner.trim()}**`);
  // Pass C: trailing whitespace only (**word **)
  formatted = formatted.replace(/\*\*([^*]+?)[\s\u00a0]+\*\*/g, (_m, inner: string) => `**${inner.trim()}**`);

  // 6. Fix missing space BEFORE opening ** when glued to preceding word (e.g. "word**bold**" -> "word **bold**")
  formatted = formatted.replace(/([a-zA-Z0-9äöüßÄÖÜ,.:;!?])\*\*([^\s*])/g, "$1 **$2");

  // 7. Fix missing space AFTER closing ** when glued to following word (e.g. "**bold**word" -> "**bold** word")
  formatted = formatted.replace(/\*\*([a-zA-Z0-9äöüßÄÖÜ][^*]*?)\*\*([a-zA-Z0-9äöüßÄÖÜ])/g, "**$1** $2");

  // 8. Fix missing spaces after punctuation before capitalized words (e.g. "mich,Sie" -> "mich, Sie").
  // Safe here because URLs, code and link destinations were masked out above —
  // a query string like "?a=1,Bar" is no longer visible to this rule.
  formatted = formatted.replace(/([,;!?])([A-ZÄÖÜ])/g, "$1 $2");

  // 9. Ensure headings have a blank line before them if preceded by text on a single newline
  formatted = formatted.replace(/([^\n])\n(#{1,6}\s)/g, "$1\n\n$2");

  return restore(formatted);
}

// ─── Content Parsing ─────────────────────────────────────────────

/**
 * Parse transcript entry content, which may be:
 * 1. JSON from backend `extractResponse()` — e.g. `{"output":[{"type":"text","text":"..."}],...}`
 * 2. Plain text (already extracted, or from fixed backend)
 * Returns the cleaned text string. Returns "" if JSON was parsed but contained no text.
 */
export function parseTranscriptContent(content: string): string {
  if (!content) return "";

  let extracted = content;

  // Quick check: does it look like JSON?
  const trimmed = content.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      const parsed = JSON.parse(trimmed);

      // Format 1: { "output": [{ "type": "text", "text": "..." }], ... }
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        const texts: string[] = [];

        // Check nested "output" array
        if (Array.isArray(parsed.output)) {
          for (const item of parsed.output) {
            if (typeof item === "string") texts.push(item);
            else if (item?.text) texts.push(String(item.text));
          }
        }

        // Check flat "output:text:*" keys
        if (texts.length === 0) {
          for (const [key, val] of Object.entries(parsed)) {
            if (!key.startsWith("output:text:")) continue;
            if (typeof val === "string") texts.push(val);
            else if (Array.isArray(val)) {
              for (const item of val) {
                if (typeof item === "string") texts.push(item);
                else if (item?.text) texts.push(String(item.text));
              }
            } else if (val && typeof val === "object" && (val as Record<string, unknown>).text) {
              texts.push(String((val as Record<string, unknown>).text));
            }
          }
        }

        if (texts.length > 0) {
          extracted = texts.join("\n\n");
        } else {
          return "";
        }
      } else if (Array.isArray(parsed)) {
        // Format 2: [{ "type": "text", "text": "..." }, ...] — top-level array
        const texts: string[] = [];
        for (const item of parsed) {
          if (typeof item === "string") texts.push(item);
          else if (item?.text) texts.push(String(item.text));
        }
        if (texts.length > 0) {
          extracted = texts.join("\n\n");
        } else {
          return "";
        }
      }
    } catch {
      // Not valid JSON — treat as plain text
    }
  }

  return formatMarkdownText(extracted);
}

// ─── Emoji Verification Parser ───────────────────────────────────

/**
 * Parse human-readable verification text with ✅/❌ emoji into structured items.
 * Returns null if no ✅/❌ lines are found (fallback to markdown rendering).
 */
export function parseEmojiVerification(content: string): StructuredItem[] | null {
  if (!content || (!content.includes("✅") && !content.includes("❌"))) return null;

  const lines = content.split("\n");
  const items: StructuredItem[] = [];
  let current: StructuredItem | null = null;
  const feedbackLines: string[] = [];

  const flushCurrent = () => {
    if (current) {
      const feedback = feedbackLines.join("\n").trim();
      if (feedback) current.feedback = feedback;
      items.push(current);
      current = null;
      feedbackLines.length = 0;
    }
  };

  const emojiLineRe = /^([✅❌])\s+\*{0,2}([^*\n:]+?)\*{0,2}\s*:\s*(.+)/;

  for (const line of lines) {
    const match = emojiLineRe.exec(line);
    if (match) {
      flushCurrent();
      current = {
        subject: (match[2] ?? "").trim(),
        passed: match[1] === "✅",
      };
    } else if (current) {
      const trimmedLine = line.trim();
      if (trimmedLine || feedbackLines.length > 0) {
        feedbackLines.push(trimmedLine);
      }
    }
  }

  flushCurrent();
  return items.length > 0 ? items : null;
}

// ─── Content Truncation ──────────────────────────────────────────

/** Max characters before content is truncated (safety net for extreme responses) */
export const MAX_CONTENT_LENGTH = 50_000;

/** Truncate long content string with a suffix notice if over maxLength */
export function truncateContent(
  text: string,
  label = "[Content truncated]",
  maxLength = MAX_CONTENT_LENGTH
): string {
  if (!text || text.length <= maxLength) return text;
  return `${text.slice(0, maxLength)}\n\n${label}`;
}

/** Safely format ISO strings, epoch seconds, or epoch millis to localized date/time strings */
export function safeFormatDate(
  val: string | number | null | undefined,
  style: "full" | "date" | "time" = "full"
): string {
  if (val == null || val === "") return "";
  try {
    const str = String(val);
    let ts =
      typeof val === "number"
        ? val
        : /^\d+(\.\d+)?$/.test(str.trim())
          ? Number(val)
          : Date.parse(str);
    if (Number.isNaN(ts)) return str;
    // If epoch seconds (10 digits), convert to millis
    if (ts > 0 && ts < 1e11) ts *= 1000;
    const date = new Date(ts);
    if (isNaN(date.getTime())) return String(val);

    if (style === "date") {
      return date.toLocaleDateString();
    }
    if (style === "time") {
      return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    }
    return date.toLocaleString([], {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return String(val);
  }
}
