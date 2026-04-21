/* ──────────────────────────────────────────────
   ScrollToBottom — Floating button
   ────────────────────────────────────────────── */

interface ScrollToBottomProps {
  visible: boolean;
  onClick: () => void;
}

export function ScrollToBottom({ visible, onClick }: ScrollToBottomProps) {
  if (!visible) return null;

  return (
    <button
      className="scroll-to-bottom"
      onClick={onClick}
      aria-label="Scroll to bottom"
      data-testid="scroll-to-bottom"
    >
      ↓
    </button>
  );
}
