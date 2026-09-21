import { ModeSwitcher } from "eddi-manager";

// Reads the sidebar tokens, so preview it on the sidebar surface.
const shell = {
  padding: 12,
  width: 240,
  background: "var(--color-sidebar)",
  border: "1px solid var(--color-sidebar-border)",
  borderRadius: 12,
} as const;

export const Expanded = () => (
  <div style={shell}>
    <ModeSwitcher />
  </div>
);

export const Collapsed = () => (
  <div style={{ ...shell, width: 64 }}>
    <ModeSwitcher collapsed />
  </div>
);
