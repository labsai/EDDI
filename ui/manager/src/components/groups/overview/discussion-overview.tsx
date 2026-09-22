import { type ReactNode, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { CircleDollarSign, Layers, MessageSquare, Users } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Badge } from "@/components/ui/badge";
import { cn, formatDuration, formatUsd } from "@/lib/utils";
import { styleDisplay } from "@/lib/discussion-styles";
import type { DiscussionDigest } from "@/hooks/use-discussion-digest";
import { PhaseRail } from "./phase-rail";
import { MemberRoster } from "./member-roster";
import { ParticipationMatrix } from "./participation-matrix";
import { bandOrder, rosterIsAnonymous, type OverviewBand } from "./style-recipe";

interface DiscussionOverviewProps {
  digest: DiscussionDigest;
  /**
   * Style-specific panels the caller already renders elsewhere — the task
   * board, the negotiation ledger, `DiscussionInsights`. Placed at the position
   * the style recipe gives the `extras` band.
   *
   * Passed in rather than rendered here because those components need data this
   * digest deliberately does not carry (artifacts and the task list are
   * persisted-only and arrive on the conversation), and duplicating them would
   * re-create the three-renderer drift this dashboard exists to avoid.
   */
  extras?: ReactNode;
  /** The outcome band's content — the decision card and synthesised answer. */
  outcome?: ReactNode;
  /**
   * Invoked when the reader picks a phase, to show them its actual turns.
   * Supplied by `DiscussionPanel` (it switches to the transcript); the phase
   * cards are inert without it rather than offering a click that does nothing.
   */
  onSelectPhase?: (phaseIndex: number) => void;
  className?: string;
}

/**
 * The overview dashboard: a second way to read one group discussion, for when
 * the transcript has grown past the point where scrolling it answers anything.
 *
 * ## One renderer, every style
 *
 * Every band reads only from {@link DiscussionDigest}, and every band returns
 * `null` when it has nothing — the pattern `DiscussionInsights` established, so
 * a caller can mount this unconditionally. Style differences are ordering and
 * emphasis (see `style-recipe.ts`), never a separate code path, which is what
 * makes a style this build has never seen render sensibly instead of blankly.
 */
export function DiscussionOverview({
  digest,
  extras,
  outcome,
  onSelectPhase,
  className,
}: DiscussionOverviewProps) {
  const { t } = useTranslation();

  if (digest.isEmpty) {
    return (
      <div
        className={cn("flex items-center justify-center p-8 text-center", className)}
        data-testid="overview-empty"
      >
        <p className="max-w-xs text-sm text-muted-foreground">
          {digest.isLive
            ? t("groups.overview.emptyLive", "The discussion is starting — the overview fills in as members speak.")
            : t("groups.overview.empty", "Nothing to show yet. The overview appears once the discussion has run.")}
        </p>
      </div>
    );
  }

  const anonymous = rosterIsAnonymous(digest.style);

  const band = (name: OverviewBand): ReactNode => {
    switch (name) {
      case "outcome":
        // The synthesised answer belongs here, not only in the transcript. A
        // completed discussion that produced no STRUCTURED decision (most
        // ROUND_TABLE and PEER_REVIEW runs) otherwise showed no conclusion at
        // all in Overview — the one thing the reader came for was reachable
        // only by switching back.
        return outcome || digest.synthesizedAnswer ? (
          <div key="outcome" className="space-y-3">
            {outcome}
            {digest.synthesizedAnswer && <SynthesisCard answer={digest.synthesizedAnswer} />}
          </div>
        ) : null;
      case "phases":
        return <PhaseRail key="phases" phases={digest.phases} onSelectPhase={onSelectPhase} />;
      case "roster":
        return <MemberRoster key="roster" members={digest.members} anonymous={anonymous} />;
      case "matrix":
        return (
          <ParticipationMatrix
            key="matrix"
            phases={digest.phases}
            members={digest.members}
            matrix={digest.matrix}
            anonymous={anonymous}
          />
        );
      case "extras":
        return extras ? <div key="extras">{extras}</div> : null;
    }
  };

  return (
    <div className={cn("space-y-4", className)} data-testid="discussion-overview">
      <OverviewHeadline digest={digest} />
      {bandOrder(digest.style).map((name) => band(name))}
    </div>
  );
}

/**
 * The synthesised answer, rendered as Markdown.
 *
 * Shown alongside a structured decision rather than instead of it: the decision
 * card carries the tally and the minority report, the synthesis carries the
 * reasoning, and neither substitutes for the other.
 */
function SynthesisCard({ answer }: { answer: string }) {
  const { t } = useTranslation();
  return (
    <section
      className="rounded-lg border border-primary/30 bg-primary/5 p-3"
      data-testid="overview-synthesis"
    >
      <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-primary">
        {t("groups.overview.synthesis", "Conclusion")}
      </h3>
      <div className="prose prose-sm dark:prose-invert max-w-none text-sm text-foreground">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{answer}</ReactMarkdown>
      </div>
    </section>
  );
}

/** The always-present top band: the question, and the four numbers worth a glance. */
function OverviewHeadline({ digest }: { digest: DiscussionDigest }) {
  const { t } = useTranslation();
  const elapsed = useElapsed(digest.startedAt, digest.endedAt, digest.isLive);

  return (
    <header className="rounded-lg border border-border bg-card p-3" data-testid="overview-headline">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        {digest.isLive && (
          <Badge variant="default" className="gap-1.5">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary-foreground" aria-hidden="true" />
            {t("groups.overview.live", "Live")}
          </Badge>
        )}
        {digest.style && <Badge variant="outline">{styleDisplay(digest.style, t).label}</Badge>}
        {digest.round > 1 && (
          <span className="text-xs text-muted-foreground">
            {t("groups.overview.round", "Round {{n}}", { n: digest.round })}
          </span>
        )}
        {elapsed !== null && <span className="text-xs text-muted-foreground">{formatDuration(elapsed)}</span>}
      </div>

      {/* Clamped, because `originalQuestion` is not always a question. A group
          is routinely asked to assess a whole document — the grant-board demo
          pastes an entire application — and rendering it in full pushed the
          rail, the roster and the matrix below the fold, which is precisely the
          wall of text this view exists to replace. Full text on hover. */}
      {digest.question && (
        <p
          className="mb-2 line-clamp-3 text-sm leading-relaxed text-foreground"
          title={digest.question}
          data-testid="overview-question"
        >
          {digest.question}
        </p>
      )}

      <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <Stat
          icon={<Layers className="h-3.5 w-3.5" aria-hidden="true" />}
          label={t("groups.overview.statPhases", "Phases")}
          value={t("groups.overview.phaseProgress", "{{done}} of {{total}}", {
            done: digest.phases.filter((p) => p.status === "done").length,
            total: digest.phases.length,
          })}
        />
        <Stat
          icon={<Users className="h-3.5 w-3.5" aria-hidden="true" />}
          label={t("groups.overview.statMembers", "Members")}
          value={String(digest.members.length)}
        />
        <Stat
          icon={<MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />}
          label={t("groups.overview.statTurns", "Turns")}
          value={String(digest.totalEntries)}
        />
        {/* Hidden rather than shown as $0.00 when nothing was attributed: an
            unpriced LLM config reports no cost at all, and "$0.00" would read
            as "this was free" instead of "this was not measured". */}
        {digest.totalCost !== null && (
          <Stat
            icon={<CircleDollarSign className="h-3.5 w-3.5" aria-hidden="true" />}
            label={t("groups.overview.statCost", "Cost")}
            value={formatUsd(digest.totalCost)}
          />
        )}
      </dl>
    </header>
  );
}

function Stat({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <div className="flex items-center gap-1.5">
      {icon}
      <dt className="sr-only">{label}</dt>
      <dd className="text-foreground">
        <span className="text-muted-foreground">{label}: </span>
        {value}
      </dd>
    </div>
  );
}

/**
 * How long the discussion has been running, in milliseconds.
 *
 * While live this ticks once a second against the clock. Once it is over the
 * span is fixed at `startedAt → endedAt`, because a finished discussion's
 * duration is how long it took — not how long ago it was. Counting to "now"
 * instead reported a week of elapsed time for a discussion reopened a week
 * later.
 *
 * Returns `null` for a missing or unparseable timestamp so the caller omits the
 * readout entirely; "Invalid Date" or a duration counted from the epoch is
 * worse than showing nothing. The interval is cleared as soon as streaming
 * stops, so a finished discussion left open in a background tab is not
 * re-rendering once a second forever.
 */
function useElapsed(startedAt: string | null, endedAt: string | null, isLive: boolean): number | null {
  const startedMs = startedAt ? Date.parse(startedAt) : Number.NaN;
  const endedMs = endedAt ? Date.parse(endedAt) : Number.NaN;
  const valid = Number.isFinite(startedMs);
  const ticking = valid && isLive;

  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!ticking) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [ticking]);

  if (!valid) return null;
  if (!isLive) {
    // No usable end timestamp on a finished discussion: report nothing rather
    // than a span measured to whenever this page happened to be opened.
    if (!Number.isFinite(endedMs)) return null;
    return Math.max(0, endedMs - startedMs);
  }
  return Math.max(0, now - startedMs);
}
