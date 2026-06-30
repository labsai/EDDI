import { ErrorBoundary } from "eddi-manager";

// A child that throws so the boundary renders its fallback (the meaningful
// state to preview — the happy path just renders children unchanged).
function Boom(): React.ReactElement {
  throw new Error("Failed to load agent configuration");
}

export const CaughtError = () => (
  <div style={{ padding: 16, maxWidth: 480 }}>
    <ErrorBoundary>
      <Boom />
    </ErrorBoundary>
  </div>
);
