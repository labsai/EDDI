import { PlatformStatus } from "eddi-manager";

// No backend in preview, so the pill settles on its offline state after the
// first probe — that is the state worth documenting anyway.
export const Pill = () => (
  <div style={{ padding: 16, display: "flex", justifyContent: "center" }}>
    <PlatformStatus />
  </div>
);
