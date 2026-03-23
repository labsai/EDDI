import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Trash2, Search, AlertTriangle, CheckCircle, Loader2 } from "lucide-react";
import { useOrphanScan, usePurgeOrphans } from "@/hooks/use-orphans";
import type { OrphanInfo } from "@/lib/api/orphans";

/** Human-readable labels for store types */
const TYPE_LABELS: Record<string, string> = {
  "ai.labs.workflow": "Workflow",
  "ai.labs.rules": "Rule Set",
  "ai.labs.apicalls": "API Calls",
  "ai.labs.output": "Output Set",
  "ai.labs.llm": "LLM",
  "ai.labs.property": "Property Setter",
  "ai.labs.dictionary": "Dictionary",
  "ai.labs.parser": "Parser",
};

export function OrphansPage() {
  const { t } = useTranslation();
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [showPurgeConfirm, setShowPurgeConfirm] = useState(false);

  const {
    data: report,
    isFetching: isScanning,
    refetch: scan,
  } = useOrphanScan(includeDeleted);

  const purge = usePurgeOrphans();

  const handleScan = useCallback(() => {
    scan();
  }, [scan]);

  const handlePurge = useCallback(() => {
    purge.mutate(includeDeleted, {
      onSuccess: (result) => {
        toast.success(
          t("orphans.purgeSuccess", {
            count: result.deletedCount,
            defaultValue: `${result.deletedCount} orphans deleted`,
          })
        );
        setShowPurgeConfirm(false);
        // Re-scan to update the list
        scan();
      },
      onError: () => toast.error(t("common.error")),
    });
  }, [purge, includeDeleted, t, scan]);

  const orphansByType = report?.orphans.reduce<Record<string, OrphanInfo[]>>(
    (groups, orphan) => {
      const key = orphan.type;
      if (!groups[key]) groups[key] = [];
      groups[key].push(orphan);
      return groups;
    },
    {}
  );

  return (
    <div className="space-y-6 p-6" data-testid="orphans-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            {t("orphans.title", "Orphan Detection")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "orphans.description",
              "Find and clean up resources not referenced by any agent or package."
            )}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2 text-sm text-muted-foreground">
            <input
              type="checkbox"
              checked={includeDeleted}
              onChange={(e) => setIncludeDeleted(e.target.checked)}
              className="h-4 w-4 rounded border-input bg-background text-primary accent-primary"
              data-testid="include-deleted-checkbox"
            />
            {t("orphans.includeDeleted", "Include soft-deleted")}
          </label>
          <button
            onClick={handleScan}
            disabled={isScanning}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
            data-testid="scan-button"
          >
            {isScanning ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Search className="h-4 w-4" />
            )}
            {t("orphans.scan", "Scan")}
          </button>
        </div>
      </div>

      {/* Results */}
      {report && (
        <div className="space-y-4">
          {/* Summary card */}
          <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                {report.totalOrphans === 0 ? (
                  <CheckCircle className="h-5 w-5 text-green-500" />
                ) : (
                  <AlertTriangle className="h-5 w-5 text-amber-500" />
                )}
                <div>
                  <p className="text-lg font-semibold text-foreground">
                    {report.totalOrphans === 0
                      ? t("orphans.noOrphans", "No orphans found")
                      : t("orphans.found", {
                          count: report.totalOrphans,
                          defaultValue: `${report.totalOrphans} orphan(s) found`,
                        })}
                  </p>
                  {report.deletedCount > 0 && (
                    <p className="text-sm text-muted-foreground">
                      {t("orphans.deletedCount", {
                        count: report.deletedCount,
                        defaultValue: `${report.deletedCount} purged`,
                      })}
                    </p>
                  )}
                </div>
              </div>

              {report.totalOrphans > 0 && (
                <div>
                  {showPurgeConfirm ? (
                    <div className="flex items-center gap-2">
                      <span className="text-sm text-destructive font-medium">
                        {t("orphans.confirmPurge", "Are you sure?")}
                      </span>
                      <button
                        onClick={handlePurge}
                        disabled={purge.isPending}
                        className="inline-flex items-center gap-1.5 rounded-lg bg-destructive px-3 py-1.5 text-xs font-medium text-destructive-foreground transition-colors hover:bg-destructive/90 disabled:opacity-50"
                        data-testid="confirm-purge-button"
                      >
                        {purge.isPending ? (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        ) : (
                          <Trash2 className="h-3.5 w-3.5" />
                        )}
                        {t("orphans.purge", "Purge All")}
                      </button>
                      <button
                        onClick={() => setShowPurgeConfirm(false)}
                        className="rounded-lg px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted"
                      >
                        {t("common.cancel", "Cancel")}
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => setShowPurgeConfirm(true)}
                      className="inline-flex items-center gap-2 rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground transition-colors hover:bg-destructive/90"
                      data-testid="purge-button"
                    >
                      <Trash2 className="h-4 w-4" />
                      {t("orphans.purge", "Purge All")}
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Orphan list grouped by type */}
          {orphansByType &&
            Object.entries(orphansByType).map(([type, orphans]) => (
              <div
                key={type}
                className="rounded-xl border border-border bg-card shadow-sm"
              >
                <div className="flex items-center justify-between border-b border-border px-4 py-3">
                  <h3 className="text-sm font-semibold text-foreground">
                    {TYPE_LABELS[type] || type}
                  </h3>
                  <span className="rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
                    {orphans.length}
                  </span>
                </div>
                <div className="divide-y divide-border">
                  {orphans.map((orphan, idx) => (
                    <div
                      key={`${orphan.resourceUri}-${idx}`}
                      className="flex items-center justify-between px-4 py-3"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium text-foreground">
                          {orphan.name}
                        </p>
                        <p className="truncate text-xs text-muted-foreground font-mono">
                          {orphan.resourceUri}
                        </p>
                      </div>
                      {orphan.deleted && (
                        <span className="ms-3 shrink-0 rounded bg-destructive/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-destructive">
                          {t("orphans.softDeleted", "Deleted")}
                        </span>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}
