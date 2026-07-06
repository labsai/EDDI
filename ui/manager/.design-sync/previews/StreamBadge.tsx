import { StreamBadge } from "eddi-manager";

export const States = () => (
  <div style={{ display: "flex", gap: 20, padding: 16, alignItems: "center" }}>
    <StreamBadge connected />
    <StreamBadge connected={false} />
  </div>
);
