/* ──────────────────────────────────────────────
   Tool cards (T1.9) and the "this turn ran tools" notice (T1.6)

   Rendered only by the two signed-in apps (Tailwind, react-i18next), like the
   team components; the Chat UI widget never resolves this entry.

   A summary is text the server wrote from a tool's result — redacted and cut to
   200 characters, but still whatever the tool returned. It is rendered as a
   React text child, never as markdown or HTML: `<img onerror=…>` in a result
   shows as those characters, not as an element.
   ────────────────────────────────────────────── */

import { useId, useState, useSyncExternalStore } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, CheckCircle2, ChevronDown, Wrench, XCircle } from "lucide-react";
import type { ToolActivityLog, ToolTurnActivity, ToolTurns } from "./tool-activity-log";
import { toolNames } from "./tool-activity-log";

/** The turns a log holds for one conversation, re-read when it changes. */
export function useToolTurns(log: ToolActivityLog, conversationId: string | null): ToolTurns {
  return useSyncExternalStore(log.subscribe, () => log.turns(conversationId));
}

type ListFormatConstructor = new (
  language: string,
  options: { style: "long"; type: "conjunction" },
) => { format(items: readonly string[]): string };

/** Tools named in a notice; the rest are counted ("+2 more"). */
const MAX_NAMED_TOOLS = 3;

/** "search, send_email and 2 more" in the reader's language. */
export function formatToolList(
  names: readonly string[],
  language: string,
  more: (n: number) => string,
): string {
  const shown = names.slice(0, MAX_NAMED_TOOLS);
  const rest = names.length - shown.length;
  const items = rest > 0 ? [...shown, more(rest)] : shown;
  // Intl.ListFormat is ES2021; the packages' `lib` is older, so it is typed here.
  const ListFormat = (Intl as unknown as { ListFormat?: ListFormatConstructor }).ListFormat;
  try {
    if (!ListFormat) throw new Error("no Intl.ListFormat");
    return new ListFormat(language, { style: "long", type: "conjunction" }).format(items);
  } catch {
    // An unknown language tag, or a runtime without ListFormat.
    return items.join(", ");
  }
}

/**
 * The tool calls of one turn, as one disclosure: collapsed, a line ("Tools
 * used: 3 · 1 failed"); open, a card per call with its tool, whether it
 * worked, and the summary as plain text.
 */
export function ToolActivityCards({ activity, className }: { activity: ToolTurnActivity; className?: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const listId = useId();
  const total = activity.results.length + activity.dropped;
  if (total === 0) return null;
  const failed = activity.results.filter((r) => !r.ok).length;
  const label = t("toolActivity.summary", "Tools used: {{n}}", { n: total });
  const failedLabel = failed > 0 ? t("toolActivity.failedCount", "{{n}} failed", { n: failed }) : null;

  return (
    <div
      className={className ?? "my-1 max-w-lg text-xs"}
      role="group"
      aria-label={t("toolActivity.region", "Tool activity")}
      data-testid="tool-activity"
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        className="inline-flex max-w-full items-center gap-1.5 rounded-md border border-border bg-muted/40 px-2 py-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        data-testid="tool-activity-toggle"
      >
        <Wrench className="h-3 w-3 shrink-0" aria-hidden="true" />
        <span className="truncate">{label}</span>
        {failedLabel && (
          <span className="shrink-0 font-medium text-destructive" data-testid="tool-activity-failed">
            · {failedLabel}
          </span>
        )}
        <ChevronDown className={`h-3 w-3 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden="true" />
      </button>
      {open && (
        <ul id={listId} className="mt-1 flex flex-col gap-1" data-testid="tool-activity-list">
          {activity.results.map((result, i) => (
            <li
              key={i}
              className="rounded-md border border-border bg-card px-2 py-1.5"
              data-testid="tool-card"
            >
              <div className="flex items-center gap-1.5">
                <span className="min-w-0 flex-1 truncate font-mono text-foreground" dir="auto" data-testid="tool-card-name">
                  {result.tool}
                </span>
                {result.ok ? (
                  <span className="inline-flex shrink-0 items-center gap-1 text-emerald-700 dark:text-emerald-400" data-testid="tool-card-status">
                    <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                    {t("toolActivity.ok", "Succeeded")}
                  </span>
                ) : (
                  <span className="inline-flex shrink-0 items-center gap-1 text-destructive" data-testid="tool-card-status">
                    <XCircle className="h-3 w-3" aria-hidden="true" />
                    {t("toolActivity.failed", "Failed")}
                  </span>
                )}
              </div>
              {/* Plain text on purpose — see the header. */}
              <p
                className="mt-0.5 whitespace-pre-wrap break-words text-muted-foreground"
                dir="auto"
                data-testid="tool-card-summary"
              >
                {result.summary || t("toolActivity.noSummary", "No details")}
              </p>
            </li>
          ))}
          {activity.dropped > 0 && (
            <li className="px-2 text-muted-foreground" data-testid="tool-activity-dropped">
              {t("toolActivity.more", "+{{n}} more", { n: activity.dropped })}
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

/**
 * What to say beside Edit or Regenerate when the turn ran tools: undo and
 * rerun take back the reply, never what a tool did. Null for a turn that ran
 * none (or none this page saw).
 */
export function ToolTurnNotice({
  activity,
  action,
  id,
}: {
  activity: ToolTurnActivity | undefined;
  action: "edit" | "regenerate";
  /** For the button's `aria-describedby`. */
  id?: string;
}) {
  const { t, i18n } = useTranslation();
  const names = toolNames(activity);
  if (names.length === 0) return null;
  const tools = formatToolList(names, i18n.language, (n) => t("toolActivity.more", "+{{n}} more", { n }));
  const text =
    action === "edit"
      ? t("toolActivity.notice.edit", "Ran {{tools}}. Editing takes back the reply, not what the tools did.", { tools })
      : t("toolActivity.notice.regenerate", "Ran {{tools}}. What they did stays, and a new reply may run them again.", {
          tools,
        });
  return (
    <span id={id} className="inline-flex items-start gap-1" data-testid="tool-turn-notice">
      <AlertTriangle className="mt-px h-3 w-3 shrink-0" aria-hidden="true" />
      <span>{text}</span>
    </span>
  );
}
