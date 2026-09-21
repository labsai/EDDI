import { ErrorState } from "eddi-manager";

export const WithRetry = () => (
  <div style={{ padding: 16, maxWidth: 480 }}>
    <ErrorState message="Failed to load agents" onRetry={() => {}} retryLabel="Try again" />
  </div>
);

export const MessageOnly = () => (
  <div style={{ padding: 16, maxWidth: 480 }}>
    <ErrorState message="Something went wrong" />
  </div>
);
