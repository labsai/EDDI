import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Plug, Plus, Search, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ViewToggle } from "@/components/shared/view-toggle";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { RefetchErrorNotice } from "@/components/shared/refetch-error-notice";
import {
  getStoredViewMode,
  setStoredViewMode,
  type ViewMode,
} from "@/components/shared/view-mode";
import { ConnectionCard } from "@/components/connections/connection-card";
import { CreateConnectionDialog } from "@/components/connections/create-connection-dialog";
import {
  AuthTypeBadge,
  BindingBadge,
} from "@/components/connections/connection-badges";
import { LinkedAccountsPanel } from "@/components/connections/linked-accounts-panel";
import {
  useConnectionDescriptors,
  useDeleteConnection,
  useDuplicateConnection,
} from "@/hooks/use-connections";
import { useConnectionLinkResult } from "@/hooks/use-connection-link-result";
import { getErrorMessage, isApiError } from "@/lib/api-client";

/**
 * Connections — the admin surface.
 *
 * Also the page the backend sends people back to after a linking round trip
 * (`ConnectionsConfig.defaultReturnTo()` is `/manage/connections`), which is why
 * the per-user panel is embedded here as well as living on its own page. A
 * non-admin who follows that redirect must land on something useful rather than
 * on a permission error, so a 403 on the config list degrades this page to
 * "your linked accounts" instead of replacing it.
 */
export function ConnectionsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [search, setSearch] = useState("");
  const [view, setView] = useState<ViewMode>(() =>
    getStoredViewMode("connections"),
  );
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{
    id: string;
    version: number;
  } | null>(null);

  const {
    data: connections,
    isLoading,
    isError,
    error,
    refetch,
  } = useConnectionDescriptors();
  const deleteMutation = useDeleteConnection();
  const duplicateMutation = useDuplicateConnection();

  const outcome = useConnectionLinkResult();
  const panelRef = useRef<HTMLDivElement>(null);

  /**
   * Arriving from a provider, the thing that just changed is below the list.
   *
   * The toast says what happened; this makes sure the row it is about is on
   * screen when the toast fades.
   */
  useEffect(() => {
    if (!outcome) return;
    // Feature-detected, not assumed. This runs in an effect, so an environment
    // without `scrollIntoView` (jsdom, and any older browser) would throw
    // *after* a link succeeded — turning a completed round trip into a blank
    // error boundary over a cosmetic nicety.
    const panel = panelRef.current;
    if (typeof panel?.scrollIntoView === "function") {
      panel.scrollIntoView({ block: "center" });
    }
  }, [outcome]);

  const notAdmin = isError && isApiError(error) && error.status === 403;

  const filtered = useMemo(() => {
    if (!connections) return [];
    if (!search.trim()) return connections;
    const q = search.toLowerCase();
    return connections.filter(
      (c) =>
        c.connectionName?.toLowerCase().includes(q) ||
        c.description?.toLowerCase().includes(q) ||
        c.authType?.toLowerCase().includes(q) ||
        c.origins.some((o) => o.toLowerCase().includes(q)),
    );
  }, [connections, search]);

  /** Per-user connections the panel below can offer a Connect button for. */
  const connectable = useMemo(
    () =>
      (connections ?? [])
        .filter((c) => c.binding === "PER_USER" && !c.unreadable)
        .map((c) => ({ name: c.connectionName, description: c.description })),
    [connections],
  );

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteMutation.mutateAsync({
        id: deleteTarget.id,
        version: deleteTarget.version,
      });
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setDeleteTarget(null);
    }
  };

  const handleDuplicate = async (id: string, version: number) => {
    try {
      await duplicateMutation.mutateAsync({ id, version });
      toast.success(
        t(
          "connections.duplicated",
          "Copied. The copy has a new name — connection names must be unique.",
        ),
      );
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  };

  const handleViewChange = (mode: ViewMode) => {
    setView(mode);
    setStoredViewMode("connections", mode);
  };

  // Gated on `!connections`: a background refetch failure keeps the last good
  // list, and replacing a usable page over a focus blip is worse than showing
  // slightly stale rows.
  const loadFailed = isError && !connections;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Plug className="h-8 w-8 text-primary" aria-hidden="true" />
            {t("pages.connections.title", "Connections")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t(
              "pages.connections.subtitle",
              "How EDDI authenticates to the systems your agents reach",
            )}
          </p>
        </div>
        {!notAdmin && (
          <Button onClick={() => setCreateOpen(true)} data-testid="create-connection-btn">
            <Plus className="h-4 w-4" aria-hidden="true" />
            {t("connections.create", "New connection")}
          </Button>
        )}
      </div>

      <div className="rounded-xl border border-primary/20 bg-primary/5 p-4">
        <p className="text-sm text-foreground">
          {t(
            "connections.guidance",
            "A connection is a credential plus the list of origins it may be sent to. Agents refer to one by name — ${connection:jira} — and EDDI resolves it per request, so the same connection can carry an organisation-wide API key or each person's own account.",
          )}
        </p>
      </div>

      {notAdmin ? (
        <div
          className="rounded-xl border border-border bg-muted/30 p-5"
          data-testid="connections-forbidden"
        >
          <div className="flex items-start gap-3">
            <ShieldAlert
              className="mt-0.5 h-5 w-5 shrink-0 text-muted-foreground"
              aria-hidden="true"
            />
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">
                {t(
                  "connections.forbiddenTitle",
                  "Only an administrator can manage connections",
                )}
              </p>
              <p className="text-sm text-muted-foreground">
                {t(
                  "connections.forbiddenBody",
                  "A connection is an outbound channel plus a credential, so editing one is restricted to the eddi-admin role. Your own linked accounts are below and need no special permission.",
                )}
              </p>
            </div>
          </div>
        </div>
      ) : (
        <>
          {isError && connections && (
            <RefetchErrorNotice onRetry={() => void refetch()} />
          )}

          <div className="flex items-center gap-3">
            <div className="relative max-w-sm flex-1">
              <Search
                className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                data-testid="connection-search"
                className="ps-9"
                placeholder={t("connections.searchPlaceholder", "Search connections…")}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
            {connections && (
              <Badge variant="secondary" className="text-xs">
                {t("connections.count", {
                  shown: filtered.length,
                  defaultValue: "{{shown}} shown",
                })}
              </Badge>
            )}
            <div className="ms-auto">
              <ViewToggle view={view} onChange={handleViewChange} />
            </div>
          </div>

          {isLoading ? (
            <div className="cq-card-grid" data-testid="connections-loading">
              {Array.from({ length: 3 }).map((_, i) => (
                <div
                  key={i}
                  className="space-y-3 rounded-xl border border-border bg-card p-5"
                >
                  <Skeleton className="h-5 w-3/4" />
                  <Skeleton className="h-4 w-1/2" />
                </div>
              ))}
            </div>
          ) : loadFailed ? (
            <ErrorState
              message={t("common.error", "Something went wrong")}
              onRetry={() => void refetch()}
              retryLabel={t("common.retry", "Retry")}
            />
          ) : filtered.length === 0 ? (
            <EmptyState
              icon={Plug}
              title={
                search
                  ? t("common.noResults", "No results found")
                  : t("connections.empty", "No connections yet")
              }
              description={
                !search
                  ? t(
                      "connections.emptyDesc",
                      "Create one to give your agents a way to authenticate to an external system.",
                    )
                  : undefined
              }
              actionLabel={
                !search ? t("connections.create", "New connection") : undefined
              }
              onAction={!search ? () => setCreateOpen(true) : undefined}
            />
          ) : view === "card" ? (
            <div className="cq-card-grid">
              {filtered.map((connection) => (
                <ConnectionCard
                  key={connection.id}
                  connection={connection}
                  onDelete={(id, version) => setDeleteTarget({ id, version })}
                  onDuplicate={(id, version) => void handleDuplicate(id, version)}
                />
              ))}
            </div>
          ) : (
            <div className="overflow-hidden rounded-xl border border-border/50">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/50">
                    <th className="px-4 py-3 text-start font-medium">
                      {t("connections.name", "Name")}
                    </th>
                    <th className="px-4 py-3 text-start font-medium">
                      {t("connections.authTypeCol", "Authentication")}
                    </th>
                    <th className="px-4 py-3 text-start font-medium">
                      {t("connections.bindingCol", "Resolves as")}
                    </th>
                    <th className="px-4 py-3 text-start font-medium">
                      {t("connections.originsCol", "Allowed origins")}
                    </th>
                    <th className="px-4 py-3 text-end font-medium">
                      {t("connections.version", "Version")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((connection) => (
                    <tr
                      key={connection.id}
                      className="cursor-pointer border-b border-border/30 transition-colors hover:bg-muted/30"
                      onClick={() =>
                        navigate(
                          `/manage/connections/${connection.id}?version=${connection.version}`,
                        )
                      }
                      data-testid={`connection-row-${connection.id}`}
                    >
                      <td className="px-4 py-3 font-medium">
                        {/* A real link, not just a row click: the row's onClick
                            is a mouse affordance only, so without this the
                            table view had no keyboard route into a connection
                            at all. It also restores middle-click and
                            open-in-new-tab, which navigate() cannot offer. */}
                        <Link
                          to={`/manage/connections/${connection.id}?version=${connection.version}`}
                          className="hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                          onClick={(e) => e.stopPropagation()}
                          data-testid={`connection-link-${connection.id}`}
                        >
                          {connection.connectionName || connection.name}
                        </Link>
                      </td>
                      <td className="px-4 py-3">
                        <AuthTypeBadge authType={connection.authType} />
                      </td>
                      <td className="px-4 py-3">
                        <BindingBadge binding={connection.binding} />
                      </td>
                      <td
                        className="px-4 py-3 font-mono text-xs text-muted-foreground"
                        dir="ltr"
                      >
                        {connection.origins.join(", ") || "—"}
                      </td>
                      <td className="px-4 py-3 text-end">
                        {t("common.versionShort", "v{{version}}", {
                          version: connection.version,
                        })}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      <div ref={panelRef} className="border-t border-border pt-6">
        <LinkedAccountsPanel connectable={connectable} />
      </div>

      <CreateConnectionDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(id, version) =>
          navigate(`/manage/connections/${id}?version=${version}`)
        }
      />

      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={() => setDeleteTarget(null)}
        title={t("connections.confirmDelete", "Delete this connection?")}
        description={t(
          "connections.confirmDeleteDesc",
          "Every account linked through it is unlinked at the same time — tokens must not outlive the connection that produced them. Agents referring to it by name will stop being able to authenticate.",
        )}
        onConfirm={() => void confirmDelete()}
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
