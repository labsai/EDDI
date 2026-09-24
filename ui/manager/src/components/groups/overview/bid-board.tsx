import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import type { DigestBid, DigestMember } from "@/hooks/use-discussion-digest";

interface BidBoardProps {
  bids: DigestBid[];
  members: DigestMember[];
  className?: string;
}

/**
 * Who bid for what — TASK_FORCE's contract-net phase (I18).
 *
 * Distinct from the task board beside it, and not redundant with it: the task
 * board shows who *holds* each task, this shows who *wanted* it. A task two
 * members bid on with high confidence and one nobody bid on are very different
 * situations that an assignment list renders identically, and the second is the
 * one worth acting on.
 *
 * Grouped by task rather than by bidder, because the contention is per task —
 * "three bids on the migration, none on the rollback plan" is the readable
 * shape. Renders nothing when no BID phase ran.
 */
export function BidBoard({ bids, members, className }: BidBoardProps) {
  const { t } = useTranslation();
  if (bids.length === 0) return null;

  const nameOf = (agentId: string) =>
    members.find((m) => m.agentId === agentId)?.displayName ?? agentId;

  // First-appearance order, so the board matches the plan's task order rather
  // than reshuffling alphabetically between renders.
  const bySubject: { subject: string; bids: DigestBid[] }[] = [];
  for (const bid of bids) {
    const group = bySubject.find((g) => g.subject === bid.subject);
    if (group) group.bids.push(bid);
    else bySubject.push({ subject: bid.subject, bids: [bid] });
  }

  return (
    <section
      className={cn("@container/bids", className)}
      data-testid="overview-bids"
      aria-label={t("groups.overview.bidsLabel", "Bids by task")}
    >
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {t("groups.overview.bids", "Who bid for what")}
      </h3>
      <ul className="space-y-1.5">
        {bySubject.map((group) => (
          <li
            key={group.subject}
            className="rounded-lg border border-border bg-secondary/30 p-2"
            data-testid="overview-bid-row"
          >
            <p className="text-xs font-medium text-foreground">{group.subject}</p>
            <div className="mt-1 flex flex-wrap gap-1">
              {group.bids.map((bid, i) => (
                <span
                  key={`${bid.agentId}-${i}`}
                  className="inline-flex items-baseline gap-1 rounded-full bg-secondary px-2 py-0.5 text-[11px] text-secondary-foreground"
                  title={bid.rationale ?? undefined}
                >
                  {nameOf(bid.agentId)}
                  {bid.estimatedComplexity && (
                    <span className="text-muted-foreground">{bid.estimatedComplexity}</span>
                  )}
                  {bid.confidence !== null && (
                    <span className="text-muted-foreground">
                      {Math.round(bid.confidence * 100)}%
                    </span>
                  )}
                </span>
              ))}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
