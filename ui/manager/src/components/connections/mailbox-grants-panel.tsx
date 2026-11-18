import { useState } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Mailbox } from "lucide-react";
import { Button } from "@eddi/ui";
import { Input } from "@eddi/app-kit/components/ui/input";
import { Skeleton } from "@eddi/app-kit/components/ui/skeleton";
import { AlertDialog } from "@eddi/app-kit/components/ui/alert-dialog";
import { GrantStatusBadge } from "@/components/connections/connection-badges";
import { navigateAway } from "@/lib/navigate-away";
import { isSafeAuthorizationUrl } from "@/lib/authorization-url";
import { getErrorMessage } from "@eddi/app-kit/lib/api-client";
import { authorizeMailbox, unlinkMailbox } from "@eddi/app-kit/lib/api/connections";
import { mailboxGrantsKey, useMailboxGrants } from "@/hooks/use-mailbox-grants";
import { useConnectionLinkResult } from "@/hooks/use-connection-link-result";

/**
 * The dedicated mailboxes linked to a `MAILBOX` connection: list, link, unlink.
 *
 * Linking is a top-level navigation to the provider, where the administrator
 * signs in **as the mailbox**; the backend stores the grant only when the
 * provider account is that address and never for the administrator's own
 * address. So the link button is disabled while the editor above holds unsaved
 * changes — leaving would discard them.
 */
export function MailboxGrantsPanel({
  connectionName,
  unsavedChanges,
}: {
  connectionName: string;
  unsavedChanges?: boolean;
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const queryClient = useQueryClient();
  // Reads ?mailboxLinked= / ?error= when the provider sends the browser back.
  useConnectionLinkResult();
  const grants = useMailboxGrants(connectionName);
  const [address, setAddress] = useState("");
  const [leaving, setLeaving] = useState(false);
  const [unlinkTarget, setUnlinkTarget] = useState<string | null>(null);

  const unlink = useMutation({
    mutationFn: (target: string) => unlinkMailbox(connectionName, target),
    onSettled: () => queryClient.invalidateQueries({ queryKey: mailboxGrantsKey(connectionName) }),
  });

  const startLink = async () => {
    const target = address.trim();
    if (!target || leaving) return;
    setLeaving(true);
    try {
      const { authorizationUrl } = await authorizeMailbox(connectionName, target, location.pathname);
      if (!isSafeAuthorizationUrl(authorizationUrl)) {
        setLeaving(false);
        toast.error(t("connections.mailbox.linkStartFailed", "Could not start linking the mailbox."));
        return;
      }
      navigateAway(authorizationUrl);
    } catch (err) {
      setLeaving(false);
      toast.error(getErrorMessage(err) || t("connections.mailbox.linkStartFailed", "Could not start linking the mailbox."));
    }
  };

  const confirmUnlink = async () => {
    if (!unlinkTarget) return;
    try {
      await unlink.mutateAsync(unlinkTarget);
      toast.success(
        t("connections.mailbox.unlinked", {
          address: unlinkTarget,
          defaultValue: "{{address}} is no longer linked. Its email integration stops reading mail until it is linked again.",
        }),
      );
    } catch (err) {
      toast.error(getErrorMessage(err) || t("connections.mailbox.unlinkFailed", "Could not unlink the mailbox."));
    } finally {
      setUnlinkTarget(null);
    }
  };

  return (
    <section className="space-y-4 rounded-xl border border-border bg-card p-6" data-testid="mailbox-grants-panel">
      <div className="flex items-start gap-2">
        <Mailbox className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <div className="space-y-1">
          <h2 className="text-sm font-semibold text-foreground">{t("connections.mailbox.title", "Mailbox grants")}</h2>
          <p className="text-xs text-muted-foreground">
            {t(
              "connections.mailbox.description",
              "Each dedicated mailbox an email integration reads through this connection must be linked once: sign in to Google as the mailbox itself, not as yourself. The grant is refused if the account is not the address.",
            )}
          </p>
        </div>
      </div>

      {grants.isLoading ? (
        <Skeleton className="h-16 w-full" />
      ) : grants.isError ? (
        <p className="text-xs text-destructive" data-testid="mailbox-grants-error">
          {getErrorMessage(grants.error) || t("connections.mailbox.loadFailed", "Could not load the linked mailboxes.")}
        </p>
      ) : (grants.data ?? []).length === 0 ? (
        <p className="text-xs text-muted-foreground" data-testid="mailbox-grants-empty">
          {t("connections.mailbox.empty", "No mailbox is linked yet.")}
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-lg border border-border" data-testid="mailbox-grants-list">
          {(grants.data ?? []).map((grant) => (
            <li key={grant.address} className="flex items-center justify-between gap-3 p-3 text-xs" data-testid="mailbox-grant-row">
              <div className="min-w-0 space-y-0.5">
                <p className="truncate font-mono text-foreground" dir="ltr">
                  {grant.address}
                </p>
                {!grant.usable && (
                  <p className="text-destructive">
                    {t("connections.mailbox.relink", "The provider rejected this grant. Link the mailbox again.")}
                  </p>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <GrantStatusBadge status={grant.status} />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setUnlinkTarget(grant.address)}
                  data-testid="mailbox-grant-unlink"
                >
                  {t("connections.mailbox.unlink", "Unlink")}
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
        <div className="flex-1 space-y-1">
          <label className="text-xs font-medium" htmlFor="mailbox-grant-address">
            {t("connections.mailbox.address", "Mailbox address")}
          </label>
          <Input
            id="mailbox-grant-address"
            data-testid="mailbox-grant-address"
            type="email"
            dir="ltr"
            className="font-mono text-xs"
            placeholder="support@company.com"
            value={address}
            onChange={(e) => setAddress(e.target.value)}
          />
        </div>
        <Button
          type="button"
          onClick={() => void startLink()}
          disabled={!address.trim() || leaving || unsavedChanges}
          data-testid="mailbox-grant-link"
        >
          {t("connections.mailbox.link", "Link mailbox")}
        </Button>
      </div>
      {unsavedChanges && (
        <p className="text-xs text-muted-foreground" data-testid="mailbox-grant-unsaved">
          {t("connections.mailbox.saveFirst", "Save or discard your changes first: linking leaves this page.")}
        </p>
      )}

      <AlertDialog
        open={unlinkTarget !== null}
        onOpenChange={(open) => {
          if (!open) setUnlinkTarget(null);
        }}
        title={t("connections.mailbox.unlinkTitle", "Unlink this mailbox?")}
        description={t("connections.mailbox.unlinkBody", {
          address: unlinkTarget ?? "",
          defaultValue:
            "{{address}} will no longer be readable through this connection, and its email integration fails as not connected until the mailbox is linked again.",
        })}
        onConfirm={() => void confirmUnlink()}
        isPending={unlink.isPending}
        confirmLabel={t("connections.mailbox.unlink", "Unlink")}
        cancelLabel={t("common.cancel", "Cancel")}
      />
    </section>
  );
}
