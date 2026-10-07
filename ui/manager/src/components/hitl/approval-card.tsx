import { useState } from "react";
import { ApprovalBanner, type ApprovalBannerProps } from "@/components/hitl/approval-banner";
import { DecisionSummary } from "@/components/hitl/decision-summary";
import { summariseDecision, type SubmittedDecision } from "@/lib/hitl-decision";

export interface ApprovalCardProps extends ApprovalBannerProps {
  /**
   * Identity of the pause on screen. Keys the card and the summary, so a
   * re-pause renders a fresh card and a receipt can never describe another
   * pause. Derive it from the PAUSE (its `hitlPausedAt`), never from query data
   * that can be withheld mid-decision — that flips the key and drops the
   * reviewer's choices.
   */
  cardKey: string;
  /** The submitted decision is being carried out (resume in flight, resumed turn running). */
  isResolving: boolean;
}

/**
 * The one approval experience, for every surface that decides: the banner, plus
 * its lifecycle. On submit the card collapses into a receipt of what was agreed
 * to while the resumed work runs. The banner stays MOUNTED but hidden so that a
 * failed resume brings it back with the reviewer's choices intact. Pending, error,
 * Retry, the guards and the step count live in `ApprovalBanner`; surfaces only
 * feed it data, so they cannot drift apart.
 */
export function ApprovalCard({ cardKey, isResolving, onDecide, isSubmitting, ...banner }: ApprovalCardProps) {
  const [submitted, setSubmitted] = useState<SubmittedDecision | null>(null);
  const showSummary = isResolving && submitted?.cardKey === cardKey;
  return (
    <>
      {showSummary && submitted && <DecisionSummary decision={submitted} />}
      <div key={cardKey} className={showSummary ? "hidden" : undefined} data-testid="approval-card-slot">
        <ApprovalBanner
          {...banner}
          isSubmitting={isSubmitting ?? isResolving}
          onDecide={(verdict, note, taskApprovals, toolDecisions) => {
            setSubmitted(
              summariseDecision({
                cardKey,
                verdict,
                note,
                pauseDetails: banner.pauseDetails,
                toolDecisions,
                pendingTaskIds: banner.pendingTaskIds,
                taskApprovals,
                phaseName: banner.pausedPhaseName,
              }),
            );
            onDecide(verdict, note, taskApprovals, toolDecisions);
          }}
        />
      </div>
    </>
  );
}
