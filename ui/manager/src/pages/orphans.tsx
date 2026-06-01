import { useState, useCallback, useMemo, useEffect } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Trash2,
  Search,
  AlertTriangle,
  CheckCircle,
  Loader2,
  ScanSearch,
  Copy,
  Link2Off,
} from "lucide-react";
import { useOrphanScan, usePurgeOrphans } from "@/hooks/use-orphans";
import type { OrphanInfo } from "@/lib/api/orphans";
import { getExtensionTypeConfig } from "@/lib/api/extensions";

/** Extract resource ID from a URI like eddi://ai.labs.rules/rulestore/rulesets/abc123?version=1 */
function extractIdFromUri(uri: string): string {
  try {
    const path = new URL(uri).pathname;
    const segments = path.split("/").filter(Boolean);
    return segments[segments.length - 1] ?? uri;
  } catch {
    // Fallback: take last path segment
    const parts = uri.split("/");
    const last = parts[parts.length - 1] ?? uri;
    return last.split("?")[0] ?? last;
  }
}

/** Extract version from URI query string */
function extractVersionFromUri(uri: string): string | null {
  try {
    const url = new URL(uri);
    return url.searchParams.get("version");
  } catch {
    const match = uri.match(/[?&]version=(\d+)/);
    return match?.[1] ?? null;
  }
}

// ─── Component ───────────────────────────────────────────────────────────────

export function OrphansPage() {
  const { t } = useTranslation();

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("orphans"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [showPurgeConfirm, setShowPurgeConfirm] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const {
    data: report,
    isFetching: isScanning,
    refetch: scan,
  } = useOrphanScan(includeDeleted);

  const purge = usePurgeOrphans();

  const handleScan = useCallback(() => {
    setSelected(new Set());
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
        setSelected(new Set());
        scan();
      },
      onError: () => toast.error(t("common.error")),
    });
  }, [purge, includeDeleted, t, scan]);

  // Group orphans by type
  const orphansByType = useMemo(() => {
    if (!report?.orphans) return null;
    return report.orphans.reduce<Record<string, OrphanInfo[]>>(
      (groups, orphan) => {
        const key = orphan.type;
        if (!groups[key]) groups[key] = [];
        groups[key].push(orphan);
        return groups;
      },
      {}
    );
  }, [report]);

  // Toggle selection
  const toggleSelect = useCallback((uri: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(uri)) next.delete(uri);
      else next.add(uri);
      return next;
    });
  }, []);

  const toggleGroup = useCallback(
    (orphans: OrphanInfo[]) => {
      setSelected((prev) => {
        const next = new Set(prev);
        const allSelected = orphans.every((o) => next.has(o.resourceUri));
        for (const o of orphans) {
          if (allSelected) next.delete(o.resourceUri);
          else next.add(o.resourceUri);
        }
        return next;
      });
    },
    []
  );

  const selectAll = useCallback(() => {
    if (!report?.orphans) return;
    setSelected((prev) => {
      const allSelected = report.orphans.every((o) => prev.has(o.resourceUri));
      if (allSelected) return new Set();
      return new Set(report.orphans.map((o) => o.resourceUri));
    });
  }, [report]);

  // Copy URI to clipboard
  const copyUri = useCallback(
    (uri: string) => {
      navigator.clipboard.writeText(uri).then(
        () => toast.success(t("common.copied", "Copied")),
        () => toast.error(t("common.error"))
      );
    },
    [t]
  );

  return (
    <div className="space-y-6 p-6" data-testid="orphans-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-foreground">
            <Link2Off className="h-6 w-6 text-primary" />
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

      {/* Pre-scan empty state */}
      {!report && !isScanning && (
        <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border bg-card/50 px-6 py-16 text-center" data-testid="pre-scan-state">
          <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10">
            <ScanSearch className="h-8 w-8 text-primary" />
          </div>
          <h2 className="text-lg font-semibold text-foreground">
            {t("orphans.scanPromptTitle", "Scan your platform")}
          </h2>
          <p className="mt-2 max-w-md text-sm text-muted-foreground">
            {t(
              "orphans.scanPromptDesc",
              "Find extension configs (rules, API calls, LLMs, outputs) that are not referenced by any workflow or agent. Clean up unused resources to keep your platform tidy."
            )}
          </p>
          <button
            onClick={handleScan}
            disabled={isScanning}
            className="mt-6 inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
            data-testid="pre-scan-button"
          >
            {isScanning ? <Loader2 className="h-4 w-4 animate-spin" /> : <ScanSearch className="h-4 w-4" />}
            {t("orphans.runScan", "Run Scan")}
          </button>
        </div>
      )}

      {/* Scanning indicator */}
      {isScanning && !report && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-border bg-card px-6 py-16 text-center">
          <Loader2 className="mb-4 h-10 w-10 animate-spin text-primary" />
          <p className="text-sm text-muted-foreground">
            {t("orphans.scanning", "Scanning resources…")}
          </p>
        </div>
      )}

      {/* Results */}
      {report && (
        <div className="space-y-4">
          {/* Summary card */}
          <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                {report.totalOrphans === 0 ? (
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-green-500/10">
                    <CheckCircle className="h-5 w-5 text-green-500" />
                  </div>
                ) : (
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-amber-500/10">
                    <AlertTriangle className="h-5 w-5 text-amber-500" />
                  </div>
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
                  <p className="text-xs text-muted-foreground">
                    {t("orphans.lastScanned", "Last scanned")}: {new Date().toLocaleTimeString()}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                {/* Select all / Delete selected */}
                {report.totalOrphans > 0 && (
                  <>
                    <button
                      onClick={selectAll}
                      className="rounded-lg border border-input bg-background px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-muted"
                      data-testid="select-all-btn"
                    >
                      {selected.size === report.orphans.length
                        ? t("orphans.deselectAll", "Deselect All")
                        : t("orphans.selectAll", "Select All")}
                    </button>
                    {selected.size > 0 && (
                      <button
                        onClick={() => setShowPurgeConfirm(true)}
                        className="inline-flex items-center gap-1.5 rounded-lg bg-destructive px-3 py-1.5 text-xs font-medium text-destructive-foreground transition-colors hover:bg-destructive/90"
                        data-testid="delete-selected-btn"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                        {t("orphans.deleteSelected", {
                          count: selected.size,
                          defaultValue: `Delete ${selected.size} selected`,
                        })}
                      </button>
                    )}

                    {/* Purge all */}
                    {showPurgeConfirm ? (
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-destructive">
                          {t("orphans.confirmPurge", "Are you sure?")}
                        </span>
                        <button
                          onClick={handlePurge}
                          disabled={purge.isPending}
                          className="inline-flex items-center gap-1.5 rounded-lg bg-destructive px-3 py-1.5 text-xs font-medium text-destructive-foreground transition-colors hover:bg-destructive/90 disabled:opacity-50"
                          data-testid="confirm-purge-button"
                        >
                          {purge.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
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
                      selected.size === 0 && (
                        <button
                          onClick={() => setShowPurgeConfirm(true)}
                          className="inline-flex items-center gap-2 rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground transition-colors hover:bg-destructive/90"
                          data-testid="purge-button"
                        >
                          <Trash2 className="h-4 w-4" />
                          {t("orphans.purge", "Purge All")}
                        </button>
                      )
                    )}
                  </>
                )}
              </div>
            </div>
          </div>

          {/* Orphan list grouped by type */}
          {orphansByType &&
            Object.entries(orphansByType).map(([type, orphans]) => {
              const config = getExtensionTypeConfig(type);
              const TypeIcon = config.icon;
              const groupAllSelected = orphans.every((o) => selected.has(o.resourceUri));

              return (
                <div
                  key={type}
                  className="rounded-xl border border-border bg-card shadow-sm overflow-hidden"
                >
                  {/* Group header */}
                  <div className="flex items-center justify-between border-b border-border px-4 py-3 bg-muted/30">
                    <div className="flex items-center gap-3">
                      <input
                        type="checkbox"
                        checked={groupAllSelected}
                        onChange={() => toggleGroup(orphans)}
                        className="h-4 w-4 rounded border-input bg-background accent-primary"
                        data-testid={`group-checkbox-${type}`}
                      />
                      <TypeIcon className={`h-4 w-4 ${config.color}`} />
                      <h3 className="text-sm font-semibold text-foreground">
                        {config.label}
                      </h3>
                    </div>
                    <span className="rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
                      {orphans.length}
                    </span>
                  </div>

                  {/* Orphan entries */}
                  <div className="divide-y divide-border/50">
                    {orphans.map((orphan, idx) => {
                      const resourceId = extractIdFromUri(orphan.resourceUri);
                      const version = extractVersionFromUri(orphan.resourceUri);

                      return (
                        <div
                          key={`${orphan.resourceUri}-${idx}`}
                          className={`flex items-center gap-3 px-4 py-3 transition-colors hover:bg-muted/30 ${
                            selected.has(orphan.resourceUri) ? "bg-primary/5" : ""
                          }`}
                        >
                          {/* Checkbox */}
                          <input
                            type="checkbox"
                            checked={selected.has(orphan.resourceUri)}
                            onChange={() => toggleSelect(orphan.resourceUri)}
                            className="h-4 w-4 shrink-0 rounded border-input bg-background accent-primary"
                            data-testid={`orphan-checkbox-${idx}`}
                          />

                          {/* Type icon */}
                          <TypeIcon className={`h-4 w-4 shrink-0 ${config.color} opacity-50`} />

                          {/* Name + URI */}
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-sm font-medium text-foreground">
                              {orphan.name || resourceId}
                            </p>
                            <div className="mt-0.5 flex items-center gap-2">
                              <span className="truncate font-mono text-[11px] text-muted-foreground">
                                {resourceId}
                              </span>
                              <button
                                onClick={() => copyUri(orphan.resourceUri)}
                                className="shrink-0 rounded p-0.5 text-muted-foreground/50 hover:text-muted-foreground transition-colors"
                                title={t("common.copy", "Copy")}
                              >
                                <Copy className="h-3 w-3" />
                              </button>
                            </div>
                          </div>

                          {/* Version badge */}
                          {version && (
                            <span className="shrink-0 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary">
                              v{version}
                            </span>
                          )}

                          {/* Deleted badge */}
                          {orphan.deleted && (
                            <span className="shrink-0 rounded bg-destructive/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-destructive">
                              {t("orphans.softDeleted", "Deleted")}
                            </span>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}
        </div>
      )}
    </div>
  );
}
