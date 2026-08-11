import { PlatformStatus } from "eddi-manager";

// No backend in preview, and the health probe carries a 5s timeout — the
// screenshot is captured long before it fails, so the card shows the CHECKING
// state (see NOTES.md "Known render warns"). A real state, honestly rendered;
// driving it to online/offline would need seeding react-query across the
// preview/bundle boundary, which two library copies make impossible.
export const Pill = () => (
  <div style={{ padding: 16, display: "flex", justifyContent: "center" }}>
    <PlatformStatus />
  </div>
);
