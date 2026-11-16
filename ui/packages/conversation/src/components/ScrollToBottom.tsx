/* ──────────────────────────────────────────────
   ScrollToBottom — Floating button
   ────────────────────────────────────────────── */

import { ArrowDown } from "lucide-react";
import { useConversationText } from "../react/hooks";

interface ScrollToBottomProps {
  visible: boolean;
  onClick: () => void;
}

export function ScrollToBottom({ visible, onClick }: ScrollToBottomProps) {
  const t = useConversationText();
  if (!visible) return null;

  return (
    <button
      className="scroll-to-bottom"
      onClick={onClick}
      aria-label={t("conversation.scroll.bottom")}
      data-testid="scroll-to-bottom"
    >
      <ArrowDown size="1em" />
    </button>
  );
}
