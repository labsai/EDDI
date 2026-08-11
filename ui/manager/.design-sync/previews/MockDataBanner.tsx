import { MockDataBanner } from "eddi-manager";

// The banner self-hides unless MSW is active. Previews run without MSW, so the
// flag is set here — a preview-only side effect, scoped to this module.
(window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ = true;

export const Active = () => (
  <div style={{ width: 720 }}>
    <MockDataBanner />
  </div>
);
