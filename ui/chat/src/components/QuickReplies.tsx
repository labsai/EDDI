/* ──────────────────────────────────────────────
   QuickReplies — Pill buttons for suggested replies
   ────────────────────────────────────────────── */

import { memo } from "react";
import type { QuickReply } from "@/types";

interface QuickRepliesProps {
  replies: QuickReply[];
  onSelect: (value: string) => void;
  /** A turn is in flight or the agent profile is still loading: a click would be dropped. */
  disabled?: boolean;
}

export const QuickReplies = memo(function QuickReplies({
  replies,
  onSelect,
  disabled = false,
}: QuickRepliesProps) {
  if (replies.length === 0) return null;

  return (
    <div className="quick-replies" data-testid="quick-replies">
      {replies.map((qr, i) => (
        <button
          key={`${qr.value}-${i}`}
          className="quick-replies__btn"
          onClick={() => onSelect(qr.value)}
          disabled={disabled}
        >
          {qr.value}
        </button>
      ))}
    </div>
  );
});
