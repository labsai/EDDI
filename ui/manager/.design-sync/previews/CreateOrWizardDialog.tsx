import type { CSSProperties } from "react";
import { CreateOrWizardDialog } from "eddi-manager";

// `transform` makes the wrapped AccessibleDialog's fixed positioning resolve to
// this box so the full dialog (title + both choice cards) renders inside the card.
const stage: CSSProperties = {
  position: "relative",
  transform: "translateZ(0)",
  height: 420,
  width: "100%",
  borderRadius: 12,
  overflow: "hidden",
};

export const Open = () => (
  <div style={stage}>
    <CreateOrWizardDialog
      open
      type="agent"
      wizardPath="/manage/agents/wizard"
      onClose={() => {}}
      onQuickCreate={() => {}}
    />
  </div>
);
