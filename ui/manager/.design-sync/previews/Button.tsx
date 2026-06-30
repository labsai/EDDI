import { Button } from "eddi-manager";
import { Plus, Trash2, Download } from "lucide-react";

const row: React.CSSProperties = {
  display: "flex",
  gap: 8,
  flexWrap: "wrap",
  alignItems: "center",
  padding: 16,
};

export const Variants = () => (
  <div style={row}>
    <Button>Primary</Button>
    <Button variant="secondary">Secondary</Button>
    <Button variant="outline">Outline</Button>
    <Button variant="ghost">Ghost</Button>
    <Button variant="destructive">Destructive</Button>
    <Button variant="link">Link</Button>
  </div>
);

export const Sizes = () => (
  <div style={row}>
    <Button size="sm">Small</Button>
    <Button size="md">Medium</Button>
    <Button size="lg">Large</Button>
    <Button size="icon">
      <Plus />
    </Button>
  </div>
);

export const WithIcons = () => (
  <div style={row}>
    <Button>
      <Plus /> New agent
    </Button>
    <Button variant="outline">
      <Download /> Export
    </Button>
    <Button variant="destructive">
      <Trash2 /> Delete
    </Button>
    <Button disabled>Disabled</Button>
  </div>
);
