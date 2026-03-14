import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Bot, Search, Plus, Upload, Wand2, ExternalLink, Trash2, Copy, Download } from "lucide-react";
import { toast } from "sonner";
import { useBotDescriptors, useDeleteBot, useDuplicateBot, groupBotsByName } from "@/hooks/use-bots";
import { BotCard } from "@/components/bots/bot-card";
import { CreateBotDialog } from "@/components/bots/create-bot-dialog";
import { ImportBotDialog } from "@/components/bots/import-bot-dialog";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import {
  ViewToggle,
  getStoredViewMode,
  setStoredViewMode,
  type ViewMode,
} from "@/components/shared/view-toggle";
import { useExportBot } from "@/hooks/use-backup";
import { cn } from "@/lib/utils";

export function BotsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("bots"));

  const { data: bots, isLoading, isError, refetch } = useBotDescriptors(100, 0, search);
  const deleteMutation = useDeleteBot();
  const duplicateMutation = useDuplicateBot();
  const exportMutation = useExportBot();

  function handleImportSuccess() {
    toast.success(t("bots.importSuccess", "Bot imported successfully"));
  }

  const groupedBots = bots ? groupBotsByName(bots) : [];

  function handleDelete(id: string, version: number) {
    setDeleteTarget({ id, version });
  }

  function confirmDelete() {
    if (deleteTarget) {
      deleteMutation.mutate(deleteTarget, {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          setDeleteTarget(null);
        },
        onError: () => toast.error(t("common.error")),
      });
    }
  }

  function handleDuplicate(id: string, version: number) {
    duplicateMutation.mutate(
      { id, version, deepCopy: true },
      {
        onSuccess: () => toast.success(t("botDetail.duplicateSuccess")),
        onError: () => toast.error(t("common.error")),
      }
    );
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode("bots", mode);
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Bot className="h-8 w-8 text-primary" />
            {t("pages.bots.title")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.bots.subtitle")}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            onClick={() => setImportOpen(true)}
            data-testid="import-bot-btn"
          >
            <Upload className="h-4 w-4" />
            {t("bots.import", "Import")}
          </Button>
          <Button variant="outline" asChild data-testid="bot-wizard-btn">
            <Link to="/manage/bots/wizard">
              <Wand2 className="h-4 w-4" />
              {t("wizard.title")}
            </Link>
          </Button>
          <Button
            onClick={() => setCreateOpen(true)}
            data-testid="create-bot-btn"
          >
            <Plus className="h-4 w-4" />
            {t("bots.createBot")}
          </Button>
        </div>
      </div>

      {/* Search bar + View toggle */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("common.search")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="bot-search"
          />
        </div>
        <ViewToggle view={view} onChange={handleViewChange} />
      </div>

      {/* Content */}
      {isLoading && (
        <div
          className={cn(
            "grid gap-4",
            "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
          )}
        >
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="rounded-xl border border-border bg-card p-5 space-y-3">
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-8 w-1/3" />
            </div>
          ))}
        </div>
      )}

      {isError && (
        <ErrorState
          message={t("common.error")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry")}
        />
      )}

      {!isLoading && !isError && groupedBots.length === 0 && (
        <EmptyState
          icon={Bot}
          title={search ? t("common.noResults") : t("bots.empty")}
          actionLabel={!search ? t("bots.createBot") : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      )}

      {!isLoading && !isError && groupedBots.length > 0 && (
        <>
          {/* Results count */}
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("bots.count", { count: groupedBots.length })}
          </p>

          {view === "card" ? (
            /* Card grid */
            <div
              className={cn(
                "grid gap-4",
                "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
              )}
              data-testid="bot-grid"
            >
              {groupedBots.map((bot) => (
                <BotCard
                  key={bot.id}
                  bot={bot}
                  onDuplicate={handleDuplicate}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          ) : (
            /* List table */
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="bot-list"
            >
              <table className="w-full">
                <thead>
                  <tr className="border-b border-border bg-secondary/50">
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.name", "Name")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.id", "ID")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.version", "Version")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.modified", "Modified")}
                    </th>
                    <th className="px-5 py-3 text-end text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.actions", "Actions")}
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {groupedBots.map((bot) => (
                    <tr
                      key={bot.id}
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/botview/${bot.id}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          {bot.name || t("bots.unnamed", "Unnamed Bot")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {bot.id.slice(0, 12)}…
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                          v{bot.version}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {new Date(bot.lastModifiedOn).toLocaleString()}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={() => handleDuplicate(bot.id, bot.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => exportMutation.mutate({ botId: bot.id, version: bot.version })}
                            disabled={exportMutation.isPending}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors disabled:opacity-50"
                            title={t("bots.export", "Export")}
                          >
                            <Download className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => handleDelete(bot.id, bot.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                            title={t("common.delete")}
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {/* Create dialog */}
      <CreateBotDialog open={createOpen} onClose={() => setCreateOpen(false)} />

      {/* Import dialog */}
      <ImportBotDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onSuccess={handleImportSuccess}
      />

      {/* Delete confirmation dialog */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("bots.confirmDelete")}
        description={t("bots.confirmDelete")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
