import { Skeleton } from "eddi-manager";

export const TextLines = () => (
  <div style={{ padding: 16, maxWidth: 360, display: "flex", flexDirection: "column", gap: 12 }}>
    <Skeleton style={{ height: 24, width: "60%" }} />
    <Skeleton style={{ height: 16, width: "100%" }} />
    <Skeleton style={{ height: 16, width: "90%" }} />
    <Skeleton style={{ height: 16, width: "40%" }} />
  </div>
);

export const AvatarRow = () => (
  <div style={{ padding: 16, display: "flex", gap: 12, alignItems: "center" }}>
    <Skeleton style={{ height: 48, width: 48, borderRadius: 9999 }} />
    <div style={{ display: "flex", flexDirection: "column", gap: 8, flex: 1 }}>
      <Skeleton style={{ height: 16, width: 140 }} />
      <Skeleton style={{ height: 12, width: 90 }} />
    </div>
  </div>
);
