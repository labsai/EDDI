import type { CSSProperties } from "react";
import { UnsavedChangesDialog } from "eddi-manager";

// `transform` makes the dialog's fixed positioning resolve to this box so the
// full dialog (title included) renders inside the card.
const stage: CSSProperties = {
  position: "relative",
  transform: "translateZ(0)",
  height: 300,
  width: "100%",
  borderRadius: 12,
  overflow: "hidden",
};

export const Open = () => (
  <div style={stage}>
    <UnsavedChangesDialog
      open
      onConfirm={() => {}}
      onCancel={() => {}}
      title="Unsaved changes"
      message="You have unsaved changes to this agent that will be lost. Are you sure you want to continue?"
    />
  </div>
);
