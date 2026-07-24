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
 * Format and normalize markdown text to ensure proper rendering across all UI surfaces:
 * 1. Fixes ATX headings without a space after `#` (e.g. `#Guten Tag` -> `# Guten Tag`)
 * 2. Fixes bold/italic missing spaces after preceding words (e.g. `noch**weitere**` -> `noch **weitere**`)
 * 3. Fixes concatenated camel-case sentence joins (e.g. `SieSind` -> `Sie Sind`, `teilIch` -> `teil Ich`)
 * 4. Ensures blank lines before headings if preceded by inline text
 */
export function formatMarkdownText(text: string): string {
  if (!text) return "";

  let formatted = text;

  // 1. Fix ATX headings missing space (e.g. "#Header" -> "# Header", "###Title" -> "### Title")
  formatted = formatted.replace(/^(#{1,6})([^\s#])/gm, "$1 $2");

  // 2. Fix bold/italic syntax glued to preceding letter/digit (e.g. "word**bold**" -> "word **bold**")
  formatted = formatted.replace(/([a-zA-Z0-9äöüßÄÖÜ])(\*{2}|_{2})([^\s*_])/g, "$1 $2");

  // 3. Fix concatenated words where two sentences/phrases were joined without space (e.g. "SieSind" -> "Sie Sind", "teilIch" -> "teil Ich")
  formatted = formatted.replace(/([a-zäöüß])([A-ZÄÖÜ])/g, "$1 $2");

  // 4. Ensure headings have a blank line before them if preceded by text on a single newline
  formatted = formatted.replace(/([^\n])\n(#{1,6}\s)/g, "$1\n\n$2");

  return formatted;
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
          // JSON was valid but no text could be extracted — return empty
          // instead of leaking raw JSON to the UI
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
 * Handles the format the backend now sends:
 *
 * ```
 * ## Task Verification Results
 *
 * ❌ **Facility Assessment**: Failed
 * RESULT is empty. No facility profile data...
 *
 * ✅ **Financial Business Case**: Passed
 * All requirements met...
 * ```
 *
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

  // Match: ✅ **Subject**: Status  or  ❌ Subject: Status
  const emojiLineRe = /^([✅❌])\s+\*{0,2}([^*:]+?)\*{0,2}\s*:\s*(.+)/;

  for (const line of lines) {
    const match = emojiLineRe.exec(line);
    if (match) {
      flushCurrent();
      current = {
        subject: (match[2] ?? "").trim(),
        passed: match[1] === "✅",
      };
    } else if (current) {
      // Skip blank lines at the very start of feedback, keep the rest
      const trimmedLine = line.trim();
      if (trimmedLine || feedbackLines.length > 0) {
        feedbackLines.push(trimmedLine);
      }
    }
    // Lines before first ✅/❌ (like "## Task Verification Results") are skipped
  }

  flushCurrent();
  return items.length > 0 ? items : null;
}

// ─── Content Truncation ──────────────────────────────────────────

/** Max characters before content is truncated (safety net for extreme responses) */
const MAX_DISPLAY_LENGTH = 50_000;

/**
 * Truncate extremely long content to prevent browser performance issues.
 * Returns the original string if under the limit.
 */
export function truncateContent(
  content: string,
  truncatedLabel: string = "[Content truncated]",
  maxLength: number = MAX_DISPLAY_LENGTH,
): string {
  if (content.length <= maxLength) return content;
  return content.substring(0, maxLength) + "\n\n" + truncatedLabel;
}

// ─── Date Formatting ─────────────────────────────────────────────

/** Safely format a date/time that may be ISO string, epoch seconds, or epoch millis */
export function safeFormatDate(value: string | number | null | undefined, style: "date" | "time" | "full" = "full"): string {
  if (value == null) return "";
  let d: Date;
  if (typeof value === "number") {
    d = new Date(value < 1e12 ? value * 1000 : value);
  } else if (/^\d+(\.\d+)?$/.test(value)) {
    const n = parseFloat(value);
    d = new Date(n < 1e12 ? n * 1000 : n);
  } else {
    d = new Date(value);
  }
  if (isNaN(d.getTime())) return String(value);
  switch (style) {
    case "date": return d.toLocaleDateString();
    case "time": return d.toLocaleTimeString();
    default: return d.toLocaleString();
  }
}
