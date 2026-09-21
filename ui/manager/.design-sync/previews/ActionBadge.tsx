import { ActionBadge } from "eddi-manager";

export const Actions = () => (
  <div style={{ display: "flex", gap: 20, padding: 16, flexWrap: "wrap", alignItems: "center" }}>
    <ActionBadge action="CREATE" />
    <ActionBadge action="UPDATE" />
    <ActionBadge action="SKIP" />
    <ActionBadge action="CONFLICT" />
  </div>
);
