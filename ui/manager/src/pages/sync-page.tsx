import { useState, useMemo, Fragment } from "react";
import { useTranslation } from "react-i18next";
import {
  RefreshCw,
  ChevronDown,
  ChevronRight,
  Loader2,
  CheckCircle,
  AlertCircle,
  ArrowRightLeft,
  Sparkles,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { SyncConfigPanel } from "@/components/agents/sync-config-panel";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
import { ActionBadge } from "@/components/shared/action-badge";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";
import {
  usePreviewSyncBatch,
  useExecuteSyncBatch,
} from "@/hooks/use-backup";
import { useInfiniteAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import type { DocumentDescriptor, ImportPreview, SyncMapping, SyncRequest } from "@/lib/api/backup";
import { parseResourceUri } from "@/lib/api/backup";

interface AgentMapping {
  remoteAgent: DocumentDescriptor;
  remoteId: string;
  remoteVersion: number | null;
  localTargetId: string | null; // null = create new
  autoMatched: boolean;
  checked: boolean;
  preview: ImportPreview | null;
}



export function SyncPage() {
  const { t } = useTranslation();

  // Connection state
  const [syncUrl, setSyncUrl] = useState("");
  const [syncAuth, setSyncAuth] = useState("");
  const [mappings, setMappings] = useState<AgentMapping[]>([]);
  const [expandedAgent, setExpandedAgent] = useState<string | null>(null);

  // Local agents for target dropdown
  const { data: agentPages } = useInfiniteAgentDescriptors();
  const localAgents = useMemo(
    () => groupAgentsByName(agentPages?.pages.flat() ?? []),
    [agentPages]
  );

  const previewBatchMutation = usePreviewSyncBatch();
  const executeBatchMutation = useExecuteSyncBatch();

  function handleConnected(agents: DocumentDescriptor[]) {
    // Auto-match by name
    const newMappings: AgentMapping[] = agents.map((remote) => {
      const { id, version } = parseResourceUri(remote.resource);
      const localMatch = localAgents.find(
        (la) => la.name?.toLowerCase() === remote.name?.toLowerCase()
      );
      return {
        remoteAgent: remote,
        remoteId: id,
        remoteVersion: version,
        localTargetId: localMatch?.id || null,
        autoMatched: !!localMatch,
        checked: true,
        preview: null,
      };
    });
    setMappings(newMappings);
    setExpandedAgent(null);
  }

  function updateMapping(idx: number, patch: Partial<AgentMapping>) {
    setMappings((prev) =>
      prev.map((m, i) => (i === idx ? { ...m, ...patch } : m))
    );
  }

  function handlePreviewAll() {
    const selected = mappings.filter((m) => m.checked);
    if (selected.length === 0) return;

    const syncMappings: SyncMapping[] = selected.map((m) => ({
      sourceAgentId: m.remoteId,
      sourceAgentVersion: m.remoteVersion,
      targetAgentId: m.localTargetId,
    }));

    previewBatchMutation.mutate(
      { sourceUrl: syncUrl, mappings: syncMappings, sourceAuth: syncAuth },
      {
        onSuccess: (previews) => {
          // Match previews back to mappings
          setMappings((prev) =>
            prev.map((m) => {
              if (!m.checked) return m;
              const p = previews.find(
                (pr) => pr.sourceAgentId === m.remoteId
              );
              return p ? { ...m, preview: p } : m;
            })
          );
        },
      }
    );
  }

  function handleSyncSelected() {
    const selected = mappings.filter((m) => m.checked && m.preview);
    if (selected.length === 0) return;

    const requests: SyncRequest[] = selected.map((m) => ({
      sourceAgentId: m.remoteId,
      sourceAgentVersion: m.remoteVersion,
      targetAgentId: m.localTargetId,
      selectedResources: null, // sync all
      workflowOrder: null,
    }));

    executeBatchMutation.mutate(
      { sourceUrl: syncUrl, requests, sourceAuth: syncAuth },
      {
        onSuccess: () => {
          // Clear previews
          setMappings((prev) => prev.map((m) => ({ ...m, preview: null })));
        },
      }
    );
  }

  const checkedCount = mappings.filter((m) => m.checked).length;
  const hasPreviewedSelection = mappings.some((m) => m.checked && m.preview);
  const totalResources = mappings
    .filter((m) => m.checked && m.preview)
    .reduce((sum, m) => sum + (m.preview?.resources.length ?? 0), 0);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-3xl font-bold text-foreground">
          {t("syncPage.title", "Agent Sync")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("syncPage.subtitle", "Synchronize agents between EDDI instances")}
        </p>
      </div>

      {/* Connection panel */}
      <section className="rounded-xl border bg-card p-5 shadow-sm">
        <h2 className="text-lg font-semibold text-foreground mb-4 flex items-center gap-2">
          <ArrowRightLeft className="h-5 w-5 text-primary" />
          {t("syncPage.connection", "Source Instance")}
        </h2>
        <SyncConfigPanel
          url={syncUrl}
          auth={syncAuth}
          onUrlChange={setSyncUrl}
          onAuthChange={setSyncAuth}
          onConnected={handleConnected}
        />
      </section>

      {/* Agent mapping */}
      {mappings.length > 0 && (
        <section className="rounded-xl border bg-card shadow-sm">
          <div className="flex items-center justify-between border-b border-border p-5">
            <h2 className="text-lg font-semibold text-foreground flex items-center gap-2">
              <RefreshCw className="h-5 w-5 text-primary" />
              {t("syncPage.agentMapping", "Agent Mapping")}
              <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                {mappings.length}
              </span>
            </h2>
            <div className="flex items-center gap-2">
              <Button
                variant="secondary"
                onClick={handlePreviewAll}
                disabled={previewBatchMutation.isPending || checkedCount === 0}
                data-testid="sync-preview-all"
              >
                {previewBatchMutation.isPending && (
                  <Loader2 className="h-4 w-4 animate-spin" />
                )}
                {t("syncPage.previewAll", "Preview All")}
              </Button>
              <Button
                onClick={handleSyncSelected}
                disabled={executeBatchMutation.isPending || !hasPreviewedSelection}
                data-testid="sync-execute-btn"
                title={!hasPreviewedSelection ? t("syncPage.previewFirst", "Preview changes before syncing") : ""}
              >
                {executeBatchMutation.isPending && (
                  <Loader2 className="h-4 w-4 animate-spin" />
                )}
                {t("syncPage.syncSelected", "Sync Selected")}
              </Button>
            </div>
          </div>

          <div className="divide-y divide-border">
            {mappings.map((m, idx) => (
              <div key={m.remoteId}>
                {/* Mapping row */}
                <div className="flex items-center gap-4 px-5 py-3">
                  <input
                    type="checkbox"
                    checked={m.checked}
                    onChange={() => updateMapping(idx, { checked: !m.checked })}
                    className="accent-primary"
                  />

                  {/* Remote agent */}
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">
                      {m.remoteAgent.name || m.remoteId}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {t("syncPage.remote", "Remote")}
                      {m.remoteVersion != null && ` · v${m.remoteVersion}`}
                      {m.autoMatched && (
                        <span className="inline-flex items-center gap-0.5 ms-1.5 text-amber-500">
                          <Sparkles className="h-3 w-3" />
                          {t("syncPage.autoMatched", "auto-matched")}
                        </span>
                      )}
                    </p>
                  </div>

                  <ArrowRightLeft className="h-4 w-4 text-muted-foreground shrink-0" />

                  {/* Local target */}
                  <div className="flex-1">
                    <select
                      value={m.localTargetId || ""}
                      onChange={(e) =>
                        updateMapping(idx, {
                          localTargetId: e.target.value || null,
                          preview: null,
                        })
                      }
                      className="w-full rounded-lg border border-input bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    >
                      <option value="">
                        {t("syncPage.createNew", "Create new")}
                      </option>
                      {localAgents.map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.name || a.id} (v{a.version})
                        </option>
                      ))}
                    </select>
                  </div>

                  {/* Preview status */}
                  <div className="shrink-0 w-24 text-end">
                    {m.preview && (
                      <button
                        onClick={() =>
                          setExpandedAgent(
                            expandedAgent === m.remoteId ? null : m.remoteId
                          )
                        }
                        className="inline-flex items-center gap-1 text-xs text-primary hover:text-primary/80"
                      >
                        {m.preview.resources.filter((r) => r.action !== "SKIP").length}{" "}
                        {t("syncPage.changes", "changes")}
                        {expandedAgent === m.remoteId ? (
                          <ChevronDown className="h-3 w-3" />
                        ) : (
                          <ChevronRight className="h-3 w-3" />
                        )}
                      </button>
                    )}
                  </div>
                </div>

                {/* Expanded detail */}
                {expandedAgent === m.remoteId && m.preview && (
                  <AgentSyncDetail preview={m.preview} />
                )}
              </div>
            ))}
          </div>

          {/* Footer summary */}
          <div className="border-t border-border px-5 py-3 flex items-center justify-between text-xs text-muted-foreground">
            <span>
              {checkedCount} {t("syncPage.agentsSelected", "agents selected")} ·{" "}
              {totalResources} {t("syncPage.totalResources", "resources")}
            </span>
            {executeBatchMutation.isSuccess && (
              <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                <CheckCircle className="h-3.5 w-3.5" />
                {t("syncPage.syncSuccess", "Sync complete")}
              </span>
            )}
            {executeBatchMutation.isError && (
              <span className="inline-flex items-center gap-1 text-destructive">
                <AlertCircle className="h-3.5 w-3.5" />
                {(executeBatchMutation.error as Error)?.message ||
                  t("syncPage.syncError", "Sync failed")}
              </span>
            )}
          </div>
        </section>
      )}

      {/* Empty state */}
      {mappings.length === 0 && (
        <section className="rounded-xl border bg-card p-12 text-center shadow-sm">
          <ArrowRightLeft className="h-12 w-12 text-muted-foreground/50 mx-auto" />
          <p className="mt-4 text-sm text-muted-foreground">
            {t("syncPage.empty", "Connect to a source instance to begin syncing agents.")}
          </p>
        </section>
      )}
    </div>
  );
}

/* ─── Per-agent resource diff table ─── */
function AgentSyncDetail({ preview }: { preview: ImportPreview }) {
  const { t } = useTranslation();
  const [expandedRow, setExpandedRow] = useState<string | null>(null);

  return (
    <div className="bg-secondary/20 px-5 pb-4">
      <div className="overflow-auto rounded-lg border max-h-64">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-secondary/80 backdrop-blur-sm">
            <tr>
              <th className="px-3 py-1.5 text-start text-xs font-medium text-muted-foreground uppercase">
                {t("importDialog.resource", "Resource")}
              </th>
              <th className="px-3 py-1.5 text-start text-xs font-medium text-muted-foreground uppercase">
                {t("importDialog.type", "Type")}
              </th>
              <th className="px-3 py-1.5 text-start text-xs font-medium text-muted-foreground uppercase">
                {t("importDialog.action", "Action")}
              </th>
              <th className="px-3 py-1.5 w-8" />
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {preview.resources.map((r) => {
              const hasDiff =
                r.action === "UPDATE" &&
                (r.sourceContent || r.targetContent);
              const isExpanded = expandedRow === r.sourceId;

              return (
                <Fragment key={r.sourceId}>
                  <tr className="group">
                    <td className="px-3 py-1.5 font-medium text-foreground">
                      <span className={r.action === "SKIP" ? "opacity-50" : ""}>
                        {r.name || r.sourceId.substring(0, 12)}
                      </span>
                    </td>
                    <td className="px-3 py-1.5">
                      <ResourceTypeBadge type={r.resourceType} />
                    </td>
                    <td className="px-3 py-1.5">
                      <ActionBadge action={r.action} />
                    </td>
                    <td className="px-3 py-1.5">
                      {hasDiff && (
                        <button
                          onClick={() =>
                            setExpandedRow(isExpanded ? null : r.sourceId)
                          }
                          className="rounded p-0.5 text-muted-foreground hover:text-foreground"
                        >
                          {isExpanded ? (
                            <ChevronDown className="h-4 w-4" />
                          ) : (
                            <ChevronRight className="h-4 w-4" />
                          )}
                        </button>
                      )}
                    </td>
                  </tr>
                  {isExpanded && hasDiff && (
                    <tr>
                      <td colSpan={4} className="px-3 py-2">
                        <ResourceDiffViewer
                          sourceContent={r.sourceContent}
                          targetContent={r.targetContent}
                        />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
