import { ResourceTypeBadge } from "eddi-manager";

export const Types = () => (
  <div style={{ display: "flex", gap: 8, padding: 16, flexWrap: "wrap", alignItems: "center" }}>
    <ResourceTypeBadge type="agent" />
    <ResourceTypeBadge type="workflow" />
    <ResourceTypeBadge type="behavior" />
    <ResourceTypeBadge type="httpcalls" />
    <ResourceTypeBadge type="llm" />
    <ResourceTypeBadge type="rag" />
    <ResourceTypeBadge type="output" />
    <ResourceTypeBadge type="property" />
  </div>
);
