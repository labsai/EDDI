import { Badge } from "eddi-manager";

const row: React.CSSProperties = {
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
  alignItems: "center",
  padding: 16,
};

export const Variants = () => (
  <div style={row}>
    <Badge>Default</Badge>
    <Badge variant="secondary">Secondary</Badge>
    <Badge variant="success">Success</Badge>
    <Badge variant="warning">Warning</Badge>
    <Badge variant="destructive">Destructive</Badge>
    <Badge variant="outline">Outline</Badge>
  </div>
);

export const StatusLabels = () => (
  <div style={row}>
    <Badge variant="success">Deployed</Badge>
    <Badge variant="warning">Draft</Badge>
    <Badge variant="destructive">Failed</Badge>
    <Badge variant="secondary">v6.1.0</Badge>
  </div>
);
