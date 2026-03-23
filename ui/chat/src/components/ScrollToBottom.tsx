/* ──────────────────────────────────────────────
   ScrollToAgenttom — Floating button
   ────────────────────────────────────────────── */

interface ScrollToAgenttomProps {
  visible: boolean;
  onClick: () => void;
}

export function ScrollToAgenttom({ visible, onClick }: ScrollToAgenttomProps) {
  if (!visible) return null;

  return (
    <button
      className="scroll-to-agenttom"
      onClick={onClick}
      aria-label="Scroll to agenttom"
      data-testid="scroll-to-agenttom"
    >
      ↓
    </button>
  );
}
