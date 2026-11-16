import { BrandWordmark } from "eddi-manager";

// The unbranded wordmark is a white glyph, so it sits on a dark surface here,
// as it does in the sidebar in dark mode.
export const Default = () => (
  <div style={{ background: "#111", padding: 16 }}>
    <BrandWordmark className="h-8" />
  </div>
);
