import { useState } from "react";
import { Sidebar } from "eddi-manager";

// The sidebar fills its parent's height, so the preview pins one.
const frame = { height: 720, display: "flex" } as const;

export const Expanded = () => {
  const [collapsed, setCollapsed] = useState(false);
  return (
    <div style={frame}>
      <Sidebar collapsed={collapsed} onToggle={() => setCollapsed((c) => !c)} />
    </div>
  );
};

export const Collapsed = () => (
  <div style={frame}>
    <Sidebar collapsed onToggle={() => {}} />
  </div>
);
