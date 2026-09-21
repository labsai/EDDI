import { useState } from "react";
import { ViewToggle } from "eddi-manager";

export const CardSelected = () => {
  const [view, setView] = useState<"card" | "list">("card");
  return (
    <div style={{ padding: 16 }}>
      <ViewToggle view={view} onChange={setView} />
    </div>
  );
};

export const ListSelected = () => {
  const [view, setView] = useState<"card" | "list">("list");
  return (
    <div style={{ padding: 16 }}>
      <ViewToggle view={view} onChange={setView} />
    </div>
  );
};
