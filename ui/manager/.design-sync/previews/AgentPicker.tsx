import { AgentPicker } from "eddi-manager";

export const Empty = () => (
  <div style={{ padding: 16, width: 340 }}>
    <AgentPicker value="" onChange={() => {}} placeholder="Select agent" />
  </div>
);

export const Selected = () => (
  <div style={{ padding: 16, width: 340 }}>
    <AgentPicker value="customer-support-bot" onChange={() => {}} />
  </div>
);
