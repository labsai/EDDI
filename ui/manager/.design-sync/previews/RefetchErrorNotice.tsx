import { RefetchErrorNotice } from "eddi-manager";

export const Default = () => (
  <div style={{ padding: 16, maxWidth: 520 }}>
    <RefetchErrorNotice onRetry={() => {}} />
  </div>
);

export const CustomMessage = () => (
  <div style={{ padding: 16, maxWidth: 520 }}>
    <RefetchErrorNotice
      onRetry={() => {}}
      message="Schedules could not refresh — showing the last poll."
    />
  </div>
);
