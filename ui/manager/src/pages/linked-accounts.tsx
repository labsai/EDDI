import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Link2 } from "lucide-react";
import { LinkedAccountsPanel } from "@/components/connections/linked-accounts-panel";
import { useConnectionDescriptors } from "@/hooks/use-connections";
import { useConnectionLinkResult } from "@/hooks/use-connection-link-result";
import { useHasRole } from "@/hooks/use-auth";

/**
 * "Your linked accounts" — the per-user page, reachable from the user menu.
 *
 * Separate from `/manage/connections` because the audiences are separate: this
 * one needs no role at all, and the config list needs `eddi-admin`. Passing its
 * own path as `returnTo` is what brings the browser back *here* after a
 * provider round trip rather than to the deployment's default page.
 *
 * There is no endpoint that lists the connections a given user *could* link —
 * `/connections/mine` returns grants that exist, and enumerating connections is
 * admin-only. So the "connect something new" list is offered when the viewer
 * happens to be an admin and simply is not there otherwise, which is the honest
 * rendering of the API. In the ordinary case the first link is started from
 * wherever the agent asked for it, and this page is where it is reviewed and
 * revoked.
 */
export function LinkedAccountsPage() {
  const { t } = useTranslation();
  const isAdmin = useHasRole("eddi-admin");

  // Announce and clear `?connected=` / `?error=` — this page is its own
  // returnTo, so the round trip lands right back here.
  useConnectionLinkResult();

  // Only asked for when it can succeed. Asking anyway would spend a guaranteed
  // 403 — and an audit-log entry — on every page view, to render nothing.
  const { data: connections } = useConnectionDescriptors(20, 0, "", isAdmin);
  const connectable = useMemo(
    () =>
      isAdmin
        ? (connections ?? [])
            .filter((c) => c.binding === "PER_USER" && !c.unreadable)
            .map((c) => ({ name: c.connectionName, description: c.description }))
        : undefined,
    [isAdmin, connections],
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <Link2 className="h-8 w-8 text-primary" aria-hidden="true" />
          {t("pages.linkedAccounts.title", "Linked accounts")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t(
            "pages.linkedAccounts.subtitle",
            "Services you have allowed EDDI to use on your behalf",
          )}
        </p>
      </div>

      <LinkedAccountsPanel connectable={connectable} bare />
    </div>
  );
}
