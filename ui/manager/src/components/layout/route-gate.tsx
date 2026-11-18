import type { ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { useMayUse } from "@eddi/app-kit/hooks/use-auth";
import { capabilityForScreen, rolesForScreen } from "@eddi/app-kit/lib/roles";
import { NoAccess } from "@/components/shared/no-access";

/**
 * The route guard: renders the "not available for your role" page instead of a
 * screen the signed-in role cannot use. It reads the same permission map as the
 * sidebar and the in-page buttons (`@eddi/app-kit/lib/roles`), keyed by the
 * current path, so a new screen is gated by one line there — not here.
 *
 * With auth off, or a token carrying no EDDI role, the map grants everything
 * and this renders its children untouched.
 */
export function RouteGate({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const may = useMayUse();
  const needed = rolesForScreen(pathname);
  if (needed && !may(needed)) {
    return <NoAccess needed={needed} capability={capabilityForScreen(pathname)} />;
  }
  return <>{children}</>;
}
