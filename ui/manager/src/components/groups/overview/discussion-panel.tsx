import { type ReactNode, useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { useDiscussionDigest } from "@/hooks/use-discussion-digest";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";
import type { DiscussionPhase, DiscussionStyle, GroupConversation } from "@/lib/api/groups";
import { DiscussionOverview } from "./discussion-overview";
import { DiscussionSplit, DiscussionViewToggle } from "./discussion-view-toggle";
import {
  getStoredDiscussionView,
  setStoredDiscussionView,
  type DiscussionViewMode,
} from "./discussion-view-mode";

interface DiscussionPanelProps {
  /**
   * Which surface this is, for the per-surface stored preference — e.g.
   * `group-detail`, `workforce-board`, `workforce-history`.
   */
  surface: string;
  /** The reader's existing transcript renderer, whichever surface this is. */
  transcript: ReactNode;
  conversation?: GroupConversation | null;
  streamState?: GroupStreamState;
  configPhases?: DiscussionPhase[] | null;
  rosterDisplayNames?: Record<string, string>;
  style?: DiscussionStyle | null;
  /** Style-specific panels for the overview's `extras` band. */
  extras?: ReactNode;
  /** The overview's `outcome` band — the decision card and synthesised answer. */
  outcome?: ReactNode;
  className?: string;
}

/**
 * Wraps a surface's existing transcript in the transcript / overview / split
 * switch.
 *
 * One component rather than three integrations for the same reason
 * `DiscussionInsights` is one component: this codebase has three independent
 * transcript renderers, and a feature wired into some of them and not others
 * has already drifted here once. A surface adopts the dashboard by wrapping
 * what it already renders — it does not reimplement the switch, the storage
 * key or the split layout.
 *
 * The transcript stays the default and is never replaced: `overview` and
 * `split` are additional ways to read the same discussion, and everything the
 * transcript does — approvals, human turns, the composer — keeps working
 * because the node is passed through untouched.
 */
export function DiscussionPanel({
  surface,
  transcript,
  conversation,
  streamState,
  configPhases,
  rosterDisplayNames,
  style,
  extras,
  outcome,
  className,
}: DiscussionPanelProps) {
  const { t } = useTranslation();
  const [view, setView] = useState<DiscussionViewMode>(() => getStoredDiscussionView(surface));
  // `undefined` means "the newest", so a discussion that gains a round while
  // this is open follows it instead of pinning the reader to the round that was
  // newest when they arrived. Picking one explicitly opts out of that.
  const [selectedRound, setSelectedRound] = useState<number | undefined>(undefined);

  const handleChange = useCallback(
    (next: DiscussionViewMode) => {
      setView(next);
      setStoredDiscussionView(surface, next);
    },
    [surface],
  );

  const digest = useDiscussionDigest({
    conversation,
    streamState,
    configPhases,
    rosterDisplayNames,
    style,
    selectedRound,
  });

  // Picking a phase in the overview shows its turns — which means switching to
  // the transcript, since that is where turns are. Owned here rather than
  // exposed as a prop no surface passed: an optional callback nobody supplies
  // is a click target that silently does nothing.
  //
  // In `split` the transcript is already on screen, so the view stays put.
  const selectPhase = useCallback(() => {
    if (view === "overview") handleChange("transcript");
  }, [view, handleChange]);

  const overview = (
    <DiscussionOverview
      digest={digest}
      extras={extras}
      outcome={outcome}
      onSelectPhase={selectPhase}
      onSelectRound={setSelectedRound}
    />
  );

  return (
    <div className={cn("flex min-h-0 flex-col", className)} data-testid="discussion-panel">
      <div className="flex shrink-0 items-center justify-end gap-2 px-4 pt-2">
        <span className="text-xs text-muted-foreground">
          {t("groups.overview.viewMode", "Discussion view")}
        </span>
        <DiscussionViewToggle view={view} onChange={handleChange} />
      </div>

      <div className="min-h-0 flex-1">
        {/* A flex column, not a plain block: every transcript renderer here
            sizes its own scroll box with `flex-1 min-h-0`, which is inert
            outside a flex container — the box would collapse to its content
            height and the overflow above it would clip with nothing to
            scroll. */}
        {view === "transcript" && (
          <div className="flex h-full min-h-0 flex-col">{transcript}</div>
        )}

        {view === "overview" && (
          <div className="h-full overflow-y-auto p-4" data-testid="discussion-panel-overview">
            {overview}
          </div>
        )}

        {/* DiscussionSplit owns the scrolling — it differs by width. */}
        {view === "split" && (
          <DiscussionSplit
            className="h-full"
            overview={<div className="p-4">{overview}</div>}
            transcript={<div className="flex min-h-0 flex-1 flex-col">{transcript}</div>}
          />
        )}
      </div>
    </div>
  );
}
