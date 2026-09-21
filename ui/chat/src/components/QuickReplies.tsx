/* ──────────────────────────────────────────────
   QuickReplies — Pill buttons for suggested replies
   ────────────────────────────────────────────── */

import type { QuickReply } from "@/types";

interface QuickRepliesProps {
  replies: QuickReply[];
  onSelect: (value: string) => void;
}

export function QuickReplies({ replies, onSelect }: QuickRepliesProps) {
  if (replies.length === 0) return null;

  return (
    <div className="quick-replies" data-testid="quick-replies">
      {replies.map((qr, i) => (
        <button
          key={`${qr.value}-${i}`}
          className="quick-replies__btn"
          onClick={() => onSelect(qr.value)}
        >
          {qr.value}
        </button>
      ))}
    </div>
  );
}
