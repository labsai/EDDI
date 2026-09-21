import type { CSSProperties } from "react";
import { AccessibleDialog, Button } from "eddi-manager";

// A `transform` on the wrapper establishes a containing block, so the dialog's
// `position: fixed` resolves to THIS box (not the viewport) and the whole dialog
// — title bar included — renders inside the preview card.
const stage: CSSProperties = {
  position: "relative",
  transform: "translateZ(0)",
  height: 320,
  width: "100%",
  borderRadius: 12,
  overflow: "hidden",
};

export const Open = () => (
  <div style={stage}>
    <AccessibleDialog open onClose={() => {}} title="Delete agent?">
      <div style={{ padding: 20, display: "flex", flexDirection: "column", gap: 16 }}>
        <p style={{ fontSize: 14, color: "var(--color-muted-foreground)" }}>
          This will permanently remove “Customer Support” and all of its
          conversation history. This action cannot be undone.
        </p>
        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <Button variant="outline">Cancel</Button>
          <Button variant="destructive">Delete agent</Button>
        </div>
      </div>
    </AccessibleDialog>
  </div>
);
