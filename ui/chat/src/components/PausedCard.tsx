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
import { t, getLocale } from "@/i18n";

interface PausedCardProps {
  status: ApprovalStatus;
  onCancel: () => void;
  cancelDisabled?: boolean;
}

/** Coarse "time left" text — seconds precision is noise at approval timescales. */
function formatRemaining(ms: number): string {
  if (ms <= 0) return t("paused.anyMoment");
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
      return t("paused.policy.approve");
    case "AUTO_REJECT":
      return t("paused.policy.reject");
    case "ABORT":
      return t("paused.policy.abort");
    default:
      return t("paused.policy.other");
  }
}

export function PausedCard({ status, onCancel, cancelDisabled }: PausedCardProps) {
  const deadline = approvalDeadline(status);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) return;
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, [deadline]);

  const tools = gatedToolNames(status);

  return (
    // No live region of its own: the card lands inside the transcript's log,
    // which announces what is added, and a nested region read the card twice
    // and again on every countdown tick.
    <div className="paused-card" data-testid="paused-card">
      <div className="paused-card__head">
        {/* A raised hand: the same "waiting on a human" icon the Manager uses
            for a paused conversation, so both UIs say it the same way. */}
        <Hand className="paused-card__icon" size="1em" aria-hidden="true" />
        <span className="paused-card__title">{t("paused.title")}</span>
      </div>

      <p className="paused-card__text">{pauseHeadline(status)}</p>

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
        <>
          {/* The countdown ticks, so it is for eyes only. Assistive technology
              gets the fixed clock time instead, which never changes under it. */}
          <p
            className="paused-card__deadline"
            aria-hidden="true"
            data-testid="paused-deadline"
          >
            {t("paused.deadline", {
              policy: describePolicy(status.timeoutPolicy),
              remaining: formatRemaining(deadline - now),
            })}
          </p>
          <p className="chat-sr-only" data-testid="paused-deadline-sr">
            {t("paused.deadlineAt", {
              policy: describePolicy(status.timeoutPolicy),
              time: new Date(deadline).toLocaleTimeString(getLocale(), {
                hour: "2-digit",
                minute: "2-digit",
              }),
            })}
          </p>
        </>
      )}

      <div className="paused-card__actions">
        <button
          type="button"
          className="paused-card__cancel"
          onClick={onCancel}
          disabled={cancelDisabled}
          data-testid="paused-cancel"
        >
          {t("paused.cancel")}
        </button>
      </div>
    </div>
  );
}
