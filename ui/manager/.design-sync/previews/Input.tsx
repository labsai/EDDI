import type { CSSProperties } from "react";
import { Input } from "eddi-manager";

const col: CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 16,
  maxWidth: 360,
};

const label: CSSProperties = {
  fontSize: 13,
  fontWeight: 500,
  marginBottom: 6,
  display: "block",
  color: "var(--color-foreground)",
};

export const Default = () => (
  <div style={col}>
    <Input placeholder="Search agents…" />
  </div>
);

export const WithLabels = () => (
  <div style={col}>
    <div>
      <label style={label}>Agent name</label>
      <Input defaultValue="Customer Support" />
    </div>
    <div>
      <label style={label}>Webhook URL</label>
      <Input placeholder="https://example.com/hook" />
    </div>
  </div>
);

export const States = () => (
  <div style={col}>
    <Input placeholder="Default" />
    <Input defaultValue="Disabled value" disabled />
    <Input type="password" defaultValue="secret-token" />
  </div>
);
