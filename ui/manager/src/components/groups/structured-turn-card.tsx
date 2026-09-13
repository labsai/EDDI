import { useTranslation } from "react-i18next";
import { Scale, Gavel, HandCoins, Lightbulb } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type {
  BargainPayload,
  BidPayload,
  RetroPayload,
  StructuredPayload,
  VotePayload,
} from "@/lib/group-payloads";

/**
 * The body of a transcript turn whose stored content is a JSON contract rather
 * than prose — a ballot, a bid sheet, a bargaining move, a retro harvest.
 *
 * One component rather than per-surface copies, for the same reason
 * `DiscussionInsights` is one component: this codebase has three independent
 * transcript renderers (the Manager's `AgentResponseCard`, the Workforce board's
 * `board-transcript`, and the history `conversation-viewer`), and the last time
 * a group feature landed in only some of them a DISSENT rendered as an ordinary
 * opinion on two of the three.
 *
 * What it replaces: these four turns previously fell through to the generic
 * JSON renderer, which printed `**bids**: [{"subject":"…","confidence":0.9,…}]`
 * — a raw JSON array in the middle of a discussion. The generic renderer no
 * longer does that either (see `readableJsonObject`), but it still labels fields
 * with the backend's own English key names; this card is the localized reading.
 *
 * Callers pass a payload from `parseStructuredPayload`, and fall back to their
 * normal prose rendering when it returns `null`.
 */

interface StructuredTurnCardProps {
  payload: StructuredPayload;
  className?: string;
}

export function StructuredTurnCard({ payload, className }: StructuredTurnCardProps) {
  switch (payload.kind) {
    case "VOTE":
      return <VoteBody payload={payload} className={className} />;
    case "BID":
      return <BidBody payload={payload} className={className} />;
    case "BARGAIN":
      return <BargainBody payload={payload} className={className} />;
    case "RETRO":
      return <RetroBody payload={payload} className={className} />;
  }
}

/** Confidence as a percentage, or nothing when the model omitted it. */
function useConfidenceLabel() {
  const { t } = useTranslation();
  return (value: number | null) =>
    value === null
      ? null
      : t("groups.payload.confidence", "{{percent}}% confident", {
          percent: Math.round(value * 100),
        });
}

function SectionHeading({
  icon: Icon,
  children,
}: {
  icon: typeof Scale;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
      <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      {children}
    </div>
  );
}

// ─── VOTE ────────────────────────────────────────────────────────

function VoteBody({ payload, className }: { payload: VotePayload; className?: string }) {
  const { t } = useTranslation();
  const confidenceLabel = useConfidenceLabel();
  const confidence = confidenceLabel(payload.confidence);

  return (
    <div className={cn("space-y-2", className)} data-testid="structured-vote">
      <SectionHeading icon={Scale}>{t("groups.payload.ballot", "Ballot")}</SectionHeading>
      {payload.options.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1.5">
          {payload.options.map((option) => (
            <Badge key={option} variant="default" data-testid="vote-option">
              {option}
            </Badge>
          ))}
          {confidence && (
            <span className="text-[11px] tabular-nums text-muted-foreground">{confidence}</span>
          )}
        </div>
      ) : (
        // A statement with no option is a ballot the tally will not count, and
        // saying so is more useful than rendering the reasoning alone as though
        // a choice had been made.
        <p className="text-xs text-muted-foreground" data-testid="vote-no-option">
          {t("groups.payload.noOption", "No option was named, so this ballot does not count towards the tally.")}
        </p>
      )}
      {payload.statement && (
        <p className="text-sm text-foreground">{payload.statement}</p>
      )}
    </div>
  );
}

// ─── BID ─────────────────────────────────────────────────────────

function BidBody({ payload, className }: { payload: BidPayload; className?: string }) {
  const { t } = useTranslation();
  const confidenceLabel = useConfidenceLabel();

  if (payload.bids.length === 0) {
    return (
      <p className={cn("text-sm text-muted-foreground", className)} data-testid="structured-bid-empty">
        {t("groups.payload.noBids", "Did not bid on any task.")}
      </p>
    );
  }

  return (
    <div className={cn("space-y-2", className)} data-testid="structured-bid">
      <SectionHeading icon={Gavel}>
        {t("groups.payload.bids", {
          defaultValue: "{{count}} bid",
          defaultValue_other: "{{count}} bids",
          count: payload.bids.length,
        })}
      </SectionHeading>
      <ul className="space-y-1.5">
        {payload.bids.map((bid, idx) => {
          const confidence = confidenceLabel(bid.confidence);
          return (
            <li
              key={`${bid.subject}-${idx}`}
              className="rounded-lg border border-border bg-background/60 p-2"
              data-testid="bid-row"
            >
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-sm font-medium text-foreground">{bid.subject}</span>
                {bid.estimatedComplexity && (
                  <Badge variant="secondary" className="text-[10px]">
                    {bid.estimatedComplexity}
                  </Badge>
                )}
                {confidence && (
                  <span className="ms-auto text-[11px] tabular-nums text-muted-foreground">
                    {confidence}
                  </span>
                )}
              </div>
              {bid.rationale && (
                <p className="mt-0.5 text-xs text-muted-foreground">{bid.rationale}</p>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// ─── BARGAIN ─────────────────────────────────────────────────────

function BargainBody({ payload, className }: { payload: BargainPayload; className?: string }) {
  const { t } = useTranslation();
  // The backend resolves a turn carrying both for the counter-proposal: new
  // terms mean the mover is not settling on existing ones. Showing the accept
  // as though it stood would describe a signature the same turn walks away from.
  const acceptSuperseded = !!payload.accept && !!payload.proposalTerms;

  return (
    <div className={cn("space-y-2", className)} data-testid="structured-bargain">
      <SectionHeading icon={HandCoins}>
        {t("groups.payload.bargain", "Bargaining move")}
      </SectionHeading>

      {payload.proposalTerms && (
        <div className="rounded-lg border border-border bg-background/60 p-2" data-testid="bargain-proposal">
          <p className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
            {t("groups.payload.counterProposal", "Counter-proposal")}
          </p>
          <p className="mt-0.5 text-sm text-foreground">{payload.proposalTerms}</p>
        </div>
      )}

      {payload.accept && (
        <p className="text-sm text-foreground" data-testid="bargain-accept">
          {acceptSuperseded
            ? t(
                "groups.payload.acceptSuperseded",
                "Also accepted {{proposal}}, which the counter-proposal above supersedes.",
                { proposal: payload.accept },
              )
            : t("groups.payload.accepted", "Accepted proposal {{proposal}}.", {
                proposal: payload.accept,
              })}
        </p>
      )}

      {payload.concessions.length > 0 && (
        <ul className="space-y-1" data-testid="bargain-concessions">
          {payload.concessions.map((concession, idx) => (
            <li
              key={`${concession.gaveUp}-${idx}`}
              className="rounded-lg border border-border bg-background/60 p-2 text-xs"
            >
              <span className="text-foreground">{concession.gaveUp}</span>
              {/* U+2192 is not bidi-mirrored: in Arabic the two spans swap but
                  the arrow keeps pointing right, so it ends up aimed back at
                  what was given up. Flipped with the writing direction. */}
              <span className="mx-1.5 text-muted-foreground rtl:-scale-x-100 inline-block" aria-hidden="true">
                →
              </span>
              <span className="text-muted-foreground">
                {t("groups.payload.inReturnFor", "in return for {{received}}", {
                  received: concession.inReturnFor,
                })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ─── RETRO ───────────────────────────────────────────────────────

function RetroBody({ payload, className }: { payload: RetroPayload; className?: string }) {
  const { t } = useTranslation();
  return (
    <div className={cn("space-y-2", className)} data-testid="structured-retro">
      <SectionHeading icon={Lightbulb}>
        {t("groups.payload.lessons", {
          defaultValue: "{{count}} lesson for the team",
          defaultValue_other: "{{count}} lessons for the team",
          count: payload.lessons.length,
        })}
      </SectionHeading>
      <ul className="space-y-1.5">
        {payload.lessons.map((lesson, idx) => (
          <li
            key={`${lesson.lesson}-${idx}`}
            className="rounded-lg border border-border bg-background/60 p-2"
            data-testid="retro-lesson"
          >
            <p className="text-sm text-foreground">{lesson.lesson}</p>
            {lesson.context && (
              <p className="mt-0.5 text-xs text-muted-foreground">{lesson.context}</p>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
