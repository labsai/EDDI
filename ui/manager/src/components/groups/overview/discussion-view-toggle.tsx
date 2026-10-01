import { type ReactNode, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Columns2, LayoutDashboard, MessageSquareText } from "lucide-react";
import { cn } from "@/lib/utils";
import { DISCUSSION_VIEW_MODES, type DiscussionViewMode } from "./discussion-view-mode";

interface DiscussionViewToggleProps {
  view: DiscussionViewMode;
  onChange: (view: DiscussionViewMode) => void;
  className?: string;
}

/**
 * Transcript / overview / split switch for a group discussion.
 *
 * Shaped after `shared/ViewToggle` — same radiogroup semantics, same arrow-key
 * behaviour — rather than reusing it, because that component's `ViewMode` is
 * the card/list union for list pages and widening it would have every list page
 * accept modes it cannot render.
 */
export function DiscussionViewToggle({ view, onChange, className }: DiscussionViewToggleProps) {
  const { t } = useTranslation();

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      e.preventDefault();
      const index = DISCUSSION_VIEW_MODES.indexOf(view);
      const delta = e.key === "ArrowRight" ? 1 : -1;
      // Wraps, matching the radiogroup pattern users already have here. The
      // modulo keeps the index in range, so the fallback is unreachable — it
      // exists only because the array is indexed.
      const next =
        DISCUSSION_VIEW_MODES[
          (index + delta + DISCUSSION_VIEW_MODES.length) % DISCUSSION_VIEW_MODES.length
        ] ?? "transcript";
      onChange(next);
      requestAnimationFrame(() => {
        document.querySelector<HTMLElement>(`[data-testid="discussion-view-${next}"]`)?.focus();
      });
    },
    [view, onChange],
  );

  const options: { mode: DiscussionViewMode; icon: ReactNode; label: string }[] = [
    {
      mode: "transcript",
      icon: <MessageSquareText className="h-4 w-4" aria-hidden="true" />,
      label: t("groups.overview.viewTranscript", "Transcript"),
    },
    {
      mode: "overview",
      icon: <LayoutDashboard className="h-4 w-4" aria-hidden="true" />,
      label: t("groups.overview.viewOverview", "Overview"),
    },
    {
      mode: "split",
      icon: <Columns2 className="h-4 w-4" aria-hidden="true" />,
      label: t("groups.overview.viewSplit", "Both"),
    },
  ];

  return (
    <div
      className={cn(
        "inline-flex items-center rounded-lg border border-input bg-background p-0.5",
        className,
      )}
      role="radiogroup"
      aria-label={t("groups.overview.viewMode", "Discussion view")}
      data-testid="discussion-view-toggle"
      onKeyDown={handleKeyDown}
    >
      {options.map(({ mode, icon, label }) => (
        <button
          key={mode}
          type="button"
          role="radio"
          aria-checked={view === mode}
          aria-label={label}
          title={label}
          tabIndex={view === mode ? 0 : -1}
          onClick={() => onChange(mode)}
          className={cn(
            "inline-flex items-center justify-center rounded-md p-1.5 transition-colors",
            view === mode
              ? "bg-secondary text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
          data-testid={`discussion-view-${mode}`}
        >
          {icon}
        </button>
      ))}
    </div>
  );
}

interface DiscussionSplitProps {
  overview: ReactNode;
  transcript: ReactNode;
  className?: string;
}

/**
 * The `split` layout: overview and transcript side by side when there is room,
 * stacked when there is not.
 *
 * Purely a container query — no width measurement, and therefore no mode that
 * "is not available". Narrowing the pane restacks the same two panels instead
 * of silently discarding the half the user chose to see, which is what a
 * width-gated `split` would do. That also keeps the preference honest: it never
 * changes to something the user did not pick.
 *
 * ## Which element scrolls, and why it changes with width
 *
 * Side by side, the two panels are independent documents of very different
 * lengths, so each gets its own scroller — a shared one would move the
 * transcript when the reader only meant to move the overview.
 *
 * Stacked, the page scrolls as a whole instead, with one exception: the
 * transcript column keeps a bounded height. Every transcript renderer here owns
 * an internal scroll box sized with `flex-1 min-h-0`, which resolves to zero
 * against an auto-height parent — the stacked transcript would collapse to
 * nothing. A viewport-relative cap gives it something to resolve against while
 * still leaving the overview above it in normal flow.
 */
export function DiscussionSplit({ overview, transcript, className }: DiscussionSplitProps) {
  return (
    <div
      className={cn(
        "@container/split overflow-y-auto @[60rem]/split:overflow-hidden",
        className,
      )}
      data-testid="discussion-split"
    >
      <div className="grid grid-cols-1 gap-4 @[60rem]/split:h-full @[60rem]/split:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        <div className="min-w-0 @[60rem]/split:h-full @[60rem]/split:min-h-0 @[60rem]/split:overflow-y-auto">
          {overview}
        </div>
        <div className="flex h-[60vh] min-w-0 flex-col @[60rem]/split:h-full @[60rem]/split:min-h-0">
          {transcript}
        </div>
      </div>
    </div>
  );
}
