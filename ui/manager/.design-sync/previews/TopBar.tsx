import { TopBar } from "eddi-manager";

// Breadcrumbs come from the router; the provider's MemoryRouter starts at "/",
// so the preview shows the root crumb. Verify after the first sync.
export const Default = () => (
  <div style={{ width: 900 }}>
    <TopBar onMenuClick={() => {}} sidebarVisible={false} />
  </div>
);
