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
  /** Invoked when the reader clicks a phase or a matrix cell in the overview. */
  onSelectPhase?: (phaseIndex: number) => void;
  onSelectMember?: (agentId: string) => void;
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
  onSelectPhase,
  onSelectMember,
  className,
}: DiscussionPanelProps) {
  const { t } = useTranslation();
  const [view, setView] = useState<DiscussionViewMode>(() => getStoredDiscussionView(surface));

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
  });

  // Reading the overview switches to the transcript so the chosen phase is
  // actually visible — following a link into a view that does not contain the
  // thing linked to is the classic version of this bug.
  const selectPhase = useCallback(
    (phaseIndex: number) => {
      if (view === "overview") handleChange("transcript");
      onSelectPhase?.(phaseIndex);
    },
    [view, handleChange, onSelectPhase],
  );

  const overview = (
    <DiscussionOverview
      digest={digest}
      extras={extras}
      outcome={outcome}
      onSelectPhase={onSelectPhase ? selectPhase : undefined}
      onSelectMember={onSelectMember}
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
        {view === "transcript" && <div className="h-full">{transcript}</div>}

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
