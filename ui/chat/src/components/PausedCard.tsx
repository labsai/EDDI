/* ──────────────────────────────────────────────
   PausedCard — the turn is waiting on a human decision
   Read-only by design: this widget shows that a reviewer must act and lets the
   user back out, but never offers Approve/Reject. Deciding belongs to a
   reviewer via Manager UI (/agents/pending-approvals) — a user approving their
   own gate defeats the oversight the pause exists to provide.
   ────────────────────────────────────────────── */

import { useEffect, useState } from "react";
import { Hand } from "lucide-react";
import {
  approvalDeadline,
  gatedToolNames,
  pauseHeadline,
  type ApprovalStatus,
} from "@/api/hitl-api";

interface PausedCardProps {
  /**
   * What approval-status says about the pause, or null while it is not known yet.
   * The stream announces a pause before it is stored, so the card is shown at
   * once from the stream's own news ("Waiting for approval…", Cancel available)
   * and enriched when the status arrives.
   */
  status: ApprovalStatus | null;
  onCancel: () => void;
  cancelDisabled?: boolean;
}

/** Coarse "time left" text — seconds precision is noise at approval timescales. */
function formatRemaining(ms: number): string {
  if (ms <= 0) return "any moment now";
  const totalMinutes = Math.floor(ms / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (totalMinutes > 0) return `${totalMinutes}m`;
  return `${Math.max(1, Math.ceil(ms / 1000))}s`;
}

/** What happens when the timeout expires, in plain language. */
function describePolicy(policy: string): string {
  switch (policy) {
    case "AUTO_APPROVE":
      return "approved automatically";
    case "AUTO_REJECT":
      return "rejected automatically";
    case "ABORT":
      return "cancelled automatically";
    default:
      return "decided automatically";
  }
}

export function PausedCard({ status, onCancel, cancelDisabled }: PausedCardProps) {
  const deadline = status ? approvalDeadline(status) : null;
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) return;
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, [deadline]);

  const tools = status ? gatedToolNames(status) : [];

  return (
    <div className="paused-card" role="status" aria-live="polite" data-testid="paused-card">
      <div className="paused-card__head">
        {/* A raised hand: the same "waiting on a human" icon the Manager uses
            for a paused conversation, so both UIs say it the same way. */}
        <Hand className="paused-card__icon" size="1em" />
        <span className="paused-card__title">Waiting for approval</span>
      </div>

      <p className="paused-card__text" data-testid="paused-headline">
        {status ? pauseHeadline(status) : "A reviewer must approve this step before I can continue."}
      </p>

      {tools.length > 0 && (
        <ul className="paused-card__tools">
          {tools.map((name) => (
            <li key={name} className="paused-card__tool">
              {name}
            </li>
          ))}
        </ul>
      )}

      {deadline !== null && (
        <p className="paused-card__deadline" data-testid="paused-deadline">
          {`Otherwise ${describePolicy(status?.timeoutPolicy ?? "")} in ${formatRemaining(deadline - now)}.`}
        </p>
      )}

      <div className="paused-card__actions">
        <button
          type="button"
          className="paused-card__cancel"
          onClick={onCancel}
          disabled={cancelDisabled}
          data-testid="paused-cancel"
        >
          Cancel this request
        </button>
      </div>
    </div>
  );
}
