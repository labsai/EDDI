import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { diffLines, diffWordsWithSpace, type ChangeObject } from "diff";
import { Equal, AlertTriangle } from "lucide-react";
import { parseRedactedJson } from "@/lib/redacted-json";
import { jsonKeyOrder, reindentJsonText } from "@/lib/reindent-json";

interface ResourceDiffViewerProps {
  sourceContent: string | null;
  targetContent: string | null;
  /**
   * What the two sides are called in the header legend. Defaults to the import
   * vocabulary ("Target" → "Source"); the approval preview passes "Stored v3" →
   * "Proposed", which is what an approver is actually looking at.
   */
  labels?: { target: string; source: string };
  /**
   * Whether to explain, above the rows, that a side is not valid JSON. Off
   * for a caller that already says so more pointedly — the approval preview —
   * so the reader does not get the same warning twice in a row.
   */
  showRawComparisonNotice?: boolean;
}

/** Unchanged lines kept either side of a change, git-style. */
const CONTEXT_LINES = 3;
/** A gap this short is not worth a fold — the fold row costs a line itself. */
const MIN_COLLAPSIBLE_GAP = 4;
const NO_GAPS: ReadonlySet<number> = new Set();
/**
 * Below this share of characters in common (indentation aside), a removed and
 * an added line are a replacement, not an edit — whole-line colour says that
 * more plainly than a scatter of highlighted fragments.
 */
const MIN_SHARED_RATIO = 0.4;
/** Word-diffing is not linear; past this a line keeps whole-line colour only. */
const MAX_WORD_DIFF_LENGTH = 10_000;
/**
 * Word-diffing runs on the main thread during render. Two long lines with
 * little in common cost over a second each, so every pair gets a ceiling and
 * the whole diff a budget; whatever runs out keeps whole-line colour.
 */
const WORD_DIFF_TIMEOUT_MS = 50;
const WORD_DIFF_BUDGET_MS = 250;

type LineKind = "added" | "removed" | "context";

interface Segment {
  text: string;
  changed: boolean;
}

interface DiffLine {
  kind: LineKind;
  text: string;
  /**
   * For a changed line paired with its counterpart on the other side: which
   * parts of it actually differ. On a long line — a system prompt is one JSON
   * string — whole-line colour alone leaves the reader hunting for the edit.
   */
  segments?: Segment[];
}

type DiffRow = { row: "line"; line: DiffLine } | { row: "gap"; id: number; count: number };

interface ComputedDiff {
  lines: DiffLine[];
  /** True when at least one side could not be parsed and the comparison is raw text. */
  rawComparison: boolean;
  /** Line counts per kind, for the header — what did NOT change matters too. */
  counts: Record<LineKind, number>;
}

/**
 * Unified diff viewer for JSON resource content, via jsdiff's `diffLines`.
 *
 * Both sides are normalised first — parsed, deep key-sorted and re-printed with
 * the same indentation — so that only real content differences show up. Without
 * that, comparing a stored document against a compact request body reports the
 * whole document as rewritten, which is the opposite of what a diff is for.
 */
export function ResourceDiffViewer({
  sourceContent,
  targetContent,
  labels,
  showRawComparisonNotice = true,
}: ResourceDiffViewerProps) {
  const { t } = useTranslation();

  const diff = useMemo<ComputedDiff | "identical" | null>(() => {
    if (!sourceContent && !targetContent) return null;

    const source = readSide(sourceContent);
    const target = readSide(targetContent);

    // One unparseable side is enough to drop the comparison to text, and the
    // reader has to be told. That side is re-indented as written; its keys
    // cannot be sorted, and sorting only the other side would turn every key
    // that sits somewhere else into a change. So the parsed side is printed in
    // the order the broken one was written in instead.
    const brokenText = source.kind === "text" ? source.content : target.kind === "text" ? target.content : null;
    const keyOrder = brokenText === null ? "sorted" : jsonKeyOrder(brokenText);
    const sourceText = printSide(source, keyOrder);
    const targetText = printSide(target, keyOrder);

    if (sourceText === targetText) return "identical";

    // A re-indented broken side nests wrongly from its first stray bracket on,
    // so comparing indentation would report every later line as changed. There
    // it is layout, not content, and is left out of the comparison.
    const changes = diffLines(targetText, sourceText, { ignoreWhitespace: brokenText !== null });
    const lines = markChangedWords(toDiffLines(changes));
    const counts: Record<LineKind, number> = { added: 0, removed: 0, context: 0 };
    for (const line of lines) counts[line.kind]++;
    return { lines, rawComparison: brokenText !== null, counts };
  }, [sourceContent, targetContent]);

  // Expanded folds are remembered against the diff they belong to. Gap ids are
  // positional, so a set kept across a content change would open the wrong run
  // in the new diff (the sync page re-previews into the same viewer).
  const [expanded, setExpanded] = useState<{ of: unknown; ids: ReadonlySet<number> }>({
    of: null,
    ids: NO_GAPS,
  });
  const expandedGaps = expanded.of === diff ? expanded.ids : NO_GAPS;
  const expandGap = (id: number) =>
    setExpanded((prev) => ({ of: diff, ids: new Set(prev.of === diff ? prev.ids : NO_GAPS).add(id) }));

  const rows = useMemo(
    () => (diff && diff !== "identical" ? collapseUnchanged(diff.lines, expandedGaps) : []),
    [diff, expandedGaps],
  );

  if (diff === null) return null;

  if (diff === "identical") {
    return (
      <div className="flex items-center gap-2 px-4 py-3 text-sm text-muted-foreground bg-secondary/30 rounded-lg">
        <Equal className="h-4 w-4" />
        {t("importDialog.contentIdentical", "Content identical")}
      </div>
    );
  }

  const targetLabel = labels?.target ?? t("importDialog.targetContent", "Target");
  const sourceLabel = labels?.source ?? t("importDialog.sourceContent", "Source");

  return (
    <div className="overflow-auto rounded-lg border bg-card text-xs font-mono max-h-80">
      {/* The header doubles as the legend: which colour is which side is the
          first thing a reader needs and the thing a bare "Target → Source"
          never said. */}
      {/* Sticky, so which side is which — and how much of the document did not
          change — stays in view while scrolling a long diff. Opaque for the
          same reason: rows would otherwise show through it. */}
      <div className="sticky top-0 z-10 flex flex-wrap items-center gap-x-2 gap-y-0.5 px-3 py-1.5 border-b bg-secondary text-[10px] text-muted-foreground">
        <span className="inline-flex items-center gap-1 text-red-700 dark:text-red-400">
          <span className="inline-block h-2 w-2 rounded-sm bg-red-500/60" aria-hidden="true" />
          {targetLabel}
        </span>
        {/* The arrow is direction, not decoration: bidi does not mirror U+2192,
            so in Arabic it would point away from the side it means. */}
        <span aria-hidden="true" className="inline-block rtl:-scale-x-100">
          →
        </span>
        <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
          <span className="inline-block h-2 w-2 rounded-sm bg-emerald-500/60" aria-hidden="true" />
          {sourceLabel}
        </span>
        {/* `unchanged`, not `count` — see the fold row below for why. */}
        <span className="ms-auto font-sans tabular-nums" data-testid="diff-summary">
          {t("importDialog.diffSummary", "Lines: {{added}} added · {{removed}} removed · {{unchanged}} unchanged", {
            added: diff.counts.added,
            removed: diff.counts.removed,
            unchanged: diff.counts.context,
          })}
        </span>
      </div>
      {diff.rawComparison && showRawComparisonNotice && (
        <p
          className="flex items-start gap-1.5 border-b bg-amber-500/10 px-3 py-1.5 text-[10px] font-sans text-amber-700 dark:text-amber-400"
          data-testid="diff-raw-comparison"
        >
          <AlertTriangle className="mt-px h-3 w-3 shrink-0" aria-hidden="true" />
          {/* Not "one side": both can fail, and claiming the other one parsed
              would be a statement this component cannot make. */}
          {t(
            "importDialog.diffRawComparison",
            "Not valid JSON — shown re-indented as written and compared line by line, ignoring indentation.",
          )}
        </p>
      )}
      <div className="p-0">
        {rows.map((entry, i) =>
          entry.row === "gap" ? (
            <button
              key={`gap-${entry.id}`}
              type="button"
              onClick={() => expandGap(entry.id)}
              // A flush full-width row, not a button-shaped control — so a
              // native element rather than the `Button` primitive, whose whole
              // cva base (rounded-lg, justify-center, h-8 px-3) would have to be
              // overridden at the call site. It does owe the primitive's focus
              // treatment though, inset so the ring stays inside the diff box.
              className="flex w-full items-center gap-2 border-s-2 border-transparent bg-secondary/30 px-2 py-0.5 text-start text-[10px] font-sans text-muted-foreground transition-colors hover:bg-secondary/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
              data-testid="diff-context-gap"
            >
              <span aria-hidden="true">…</span>
              {/* `lines`, not `count` — `count` would put i18next into plural
                  lookup (`_one`/`_other`), and a gap is never shorter than
                  MIN_COLLAPSIBLE_GAP anyway. "Show", not "click to show":
                  this is reachable by keyboard too. */}
              {t("importDialog.diffHiddenLines", "Show {{lines}} unchanged lines", {
                lines: entry.count,
              })}
            </button>
          ) : (
            <div
              key={`line-${i}`}
              // Rows are what the tests assert on, so they carry a selector —
              // and the kind alongside it, because "this line is an ADDITION"
              // is the assertion that matters and colour cannot be queried.
              data-testid="diff-line"
              data-diff-kind={entry.line.kind}
              className={`flex ${
                entry.line.kind === "added"
                  ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-s-2 border-emerald-500"
                  : entry.line.kind === "removed"
                    ? "bg-red-500/10 text-red-700 dark:text-red-400 border-s-2 border-red-500"
                    : "text-muted-foreground border-s-2 border-transparent"
              }`}
            >
              <span className="inline-block w-6 shrink-0 text-end pe-2 text-muted-foreground/50 select-none">
                {entry.line.kind === "added" ? "+" : entry.line.kind === "removed" ? "−" : " "}
              </span>
              {/* `whitespace-pre-wrap` keeps the JSON indentation a plain div
                  would collapse; `wrap-anywhere` wraps an over-long line
                  instead of pushing every other line behind a scrollbar. */}
              <span className="min-w-0 flex-1 whitespace-pre-wrap wrap-anywhere">
                {entry.line.segments
                  ? entry.line.segments.map((segment, s) =>
                      !segment.changed ? (
                        segment.text
                      ) : entry.line.kind === "added" ? (
                        // <ins>/<del> rather than a styled span, so the markup
                        // says which words changed and not only the colour —
                        // wherever that is exposed, and to the tests.
                        <ins key={s} className="rounded-sm bg-emerald-500/30 no-underline" data-testid="diff-changed-words">
                          {segment.text}
                        </ins>
                      ) : (
                        <del key={s} className="rounded-sm bg-red-500/30 no-underline" data-testid="diff-changed-words">
                          {segment.text}
                        </del>
                      ),
                    )
                  : entry.line.text || " "}
              </span>
            </div>
          ),
        )}
      </div>
    </div>
  );
}

/** One side of the comparison, read but not yet printed. */
type Side = { kind: "empty" } | { kind: "json"; value: unknown } | { kind: "text"; content: string };

function readSide(content: string | null): Side {
  if (!content) return { kind: "empty" };

  // Tolerant of the one malformed shape EDDI's redaction filter produces — see
  // `redacted-json.ts`. Without it a single credential field costs the reader
  // the key-sorted comparison for the whole document.
  const parsed = parseRedactedJson(content);
  return parsed.ok ? { kind: "json", value: parsed.value } : { kind: "text", content };
}

/**
 * Print a side for diffing: deep key-sorted when both sides parse, otherwise
 * in the key order of the side that did not (see `jsonKeyOrder`).
 */
function printSide(side: Side, keyOrder: "sorted" | Map<string, number>): string {
  switch (side.kind) {
    case "empty":
      return "";
    case "json": {
      // A key the broken side never names ranks last, keeping its own order.
      const compare =
        keyOrder === "sorted"
          ? undefined
          : (a: string, b: string) => (keyOrder.get(a) ?? keyOrder.size) - (keyOrder.get(b) ?? keyOrder.size);
      return JSON.stringify(deepSortKeys(side.value, compare), null, 2);
    }
    case "text":
      // Re-indenting is for text that is trying to be JSON. Anything else is
      // compared as it came: its line breaks are its layout.
      return /^\s*[[{]/.test(side.content) ? reindentJsonText(side.content) : side.content;
  }
}

/** Re-key every object, recursively, in `compare` order (default: alphabetical). */
function deepSortKeys(value: unknown, compare?: (a: string, b: string) => number): unknown {
  if (Array.isArray(value)) return value.map((item) => deepSortKeys(item, compare));
  if (value !== null && typeof value === "object") {
    return Object.keys(value as Record<string, unknown>)
      .sort(compare)
      .reduce<Record<string, unknown>>((acc, key) => {
        acc[key] = deepSortKeys((value as Record<string, unknown>)[key], compare);
        return acc;
      }, {});
  }
  return value;
}

/** Flatten jsdiff chunks into one line per rendered row. */
function toDiffLines(changes: ChangeObject<string>[]): DiffLine[] {
  const lines: DiffLine[] = [];
  for (const change of changes) {
    const kind: LineKind = change.added ? "added" : change.removed ? "removed" : "context";
    const parts = change.value.split("\n");
    // Each chunk ends on the newline that terminates its last line.
    if (parts[parts.length - 1] === "") parts.pop();
    for (const text of parts) lines.push({ kind, text });
  }
  return lines;
}

/**
 * Pair each run of removed lines with the run of added lines right after it,
 * line by line, and mark the words that differ within each pair.
 *
 * jsdiff emits a replaced block as removals then additions, so index pairing
 * lines up an edited field with its new value. Where the counts differ the
 * extra lines stay unpaired, and a pair with too little in common is left
 * alone — pairing is a guess, and a bad guess must degrade to plain whole-line
 * colour, never to highlighting that implies an edit which was not made.
 */
function markChangedWords(lines: DiffLine[]): DiffLine[] {
  const deadline = performance.now() + WORD_DIFF_BUDGET_MS;
  let i = 0;
  while (i < lines.length) {
    if (lines[i]!.kind !== "removed") {
      i++;
      continue;
    }
    const removedStart = i;
    while (i < lines.length && lines[i]!.kind === "removed") i++;
    const addedStart = i;
    while (i < lines.length && lines[i]!.kind === "added") i++;

    const pairs = Math.min(addedStart - removedStart, i - addedStart);
    for (let p = 0; p < pairs; p++) {
      const remaining = deadline - performance.now();
      if (remaining <= 0) return lines;
      const removed = lines[removedStart + p]!;
      const added = lines[addedStart + p]!;
      const segments = wordSegments(removed.text, added.text, Math.min(WORD_DIFF_TIMEOUT_MS, remaining));
      if (segments) {
        removed.segments = segments.removed;
        added.segments = segments.added;
      }
    }
  }
  return lines;
}

function wordSegments(
  before: string,
  after: string,
  timeout: number,
): { removed: Segment[]; added: Segment[] } | null {
  if (before.length > MAX_WORD_DIFF_LENGTH || after.length > MAX_WORD_DIFF_LENGTH) return null;

  // `undefined` when the timeout ran out first.
  const parts = diffWordsWithSpace(before, after, { timeout });
  if (!parts) return null;

  const removed: Segment[] = [];
  const added: Segment[] = [];
  let shared = 0;
  for (const part of parts) {
    if (part.added) added.push({ text: part.value, changed: true });
    else if (part.removed) removed.push({ text: part.value, changed: true });
    else {
      shared += part.value.length;
      removed.push({ text: part.value, changed: false });
      added.push({ text: part.value, changed: false });
    }
  }

  // Shared indentation says nothing about whether two lines are the same field.
  const indent = Math.min(leadingWhitespace(before), leadingWhitespace(after));
  const longest = Math.max(before.length, after.length) - indent;
  if (longest <= 0 || shared - indent < MIN_SHARED_RATIO * longest) return null;
  return { removed, added };
}

function leadingWhitespace(text: string): number {
  return text.length - text.trimStart().length;
}

/**
 * Fold long runs of unchanged lines away.
 *
 * The point of the diff is the change; on a 400-line agent config, rendering
 * every identical line back puts the approver right back to finding the edit by
 * eye. Each fold is expandable — nothing is hidden that cannot be got back.
 */
function collapseUnchanged(lines: DiffLine[], expandedGaps: ReadonlySet<number>): DiffRow[] {
  const keep = lines.map((line) => line.kind !== "context");
  lines.forEach((line, i) => {
    if (line.kind === "context") return;
    for (let j = Math.max(0, i - CONTEXT_LINES); j <= Math.min(lines.length - 1, i + CONTEXT_LINES); j++) {
      keep[j] = true;
    }
  });

  const rows: DiffRow[] = [];
  let gapId = 0;
  let i = 0;
  while (i < lines.length) {
    const start = i;
    const kept = keep[i];
    while (i < lines.length && keep[i] === kept) i++;
    const run = lines.slice(start, i);

    // Gap ids are assigned to every dropped run, folded or not, so that adding
    // a short run above a long one does not renumber what the user expanded.
    const id = kept ? -1 : gapId++;
    if (kept || run.length < MIN_COLLAPSIBLE_GAP || expandedGaps.has(id)) {
      for (const line of run) rows.push({ row: "line", line });
    } else {
      rows.push({ row: "gap", id, count: run.length });
    }
  }
  return rows;
}
