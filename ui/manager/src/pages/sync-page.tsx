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
import type {
  BatchSyncExecution,
  DocumentDescriptor,
  ImportPreview,
  SyncMapping,
  SyncRequest,
} from "@/lib/api/backup";
import { hasFailures, parseResourceUri } from "@/lib/api/backup";
import { getErrorMessage } from "@/lib/api-client";

interface AgentMapping {
  remoteAgent: DocumentDescriptor;
  remoteId: string;
  remoteVersion: number | null;
  localTargetId: string | null; // null = create new
  autoMatched: boolean;
  checked: boolean;
  preview: ImportPreview | null;
}



/** A preview entry standing in for one the source did not return. */
function missingPreview(sourceAgentId: string, message: string): ImportPreview {
  return {
    sourceAgentId,
    sourceAgentName: null,
    targetAgentId: null,
    targetAgentName: null,
    resources: [],
    error: message,
  };
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

  const noPreviewMessage = t(
    "syncPage.noPreviewReturned",
    "The source returned no preview for this agent."
  );

  // The agent list and every preview belong to the source they were fetched
  // from. Editing the URL or credentials used to keep both, so a sync could run
  // against a different instance than the one that was previewed.
  function handleSourceChange(apply: () => void) {
    apply();
    if (mappings.length > 0) {
      setMappings([]);
      setExpandedAgent(null);
      previewBatchMutation.reset();
      executeBatchMutation.reset();
    }
  }

  function handlePreviewAll() {
    const selected = mappings.filter((m) => m.checked);
    if (selected.length === 0) return;

    const syncMappings: SyncMapping[] = selected.map((m) => ({
      sourceAgentId: m.remoteId,
      sourceAgentVersion: m.remoteVersion,
      targetAgentId: m.localTargetId,
    }));

    // The previous run's outcome describes resources this preview is about to
    // replace; leaving it on screen reads as the result of what is about to happen.
    executeBatchMutation.reset();

    // Previews from an earlier run must not survive this one. They used to:
    // a mapping the new response did not cover (or every mapping, when the
    // request failed) kept its OLD preview, and "Sync Selected" — which is
    // enabled by any previewed selection — then synced on the strength of a
    // diff nobody had just looked at.
    setMappings((prev) =>
      prev.map((m) => (m.checked ? { ...m, preview: null } : m))
    );

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
              return {
                ...m,
                preview: p ?? missingPreview(m.remoteId, noPreviewMessage),
              };
            })
          );
        },
      }
    );
  }

  function handleSyncSelected() {
    const selected = mappings.filter((m) => m.checked && m.preview && !m.preview.error);
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
        onSuccess: (execution) => {
          // A mapping that had no local target now has one — the agent this run
          // created. Without adopting it, the next Preview + Sync sends
          // targetAgentId: null again and creates a SECOND copy of the same agent.
          const createdBySource = new Map<string, string>();
          for (const result of execution.results) {
            if (!result.targetAgentId && result.result?.agentUri) {
              const { id } = parseResourceUri(result.result.agentUri);
              if (id) createdBySource.set(result.sourceAgentId, id);
            }
          }
          setMappings((prev) =>
            prev.map((m) => ({
              ...m,
              localTargetId: m.localTargetId ?? createdBySource.get(m.remoteId) ?? null,
              preview: null,
            }))
          );
        },
      }
    );
  }

  const checkedCount = mappings.filter((m) => m.checked).length;
  const hasPreviewedSelection = mappings.some((m) => m.checked && m.preview && !m.preview.error);
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
          onUrlChange={(v) => handleSourceChange(() => setSyncUrl(v))}
          onAuthChange={(v) => handleSourceChange(() => setSyncAuth(v))}
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
                    {m.preview?.error && (
                      <span
                        className="inline-flex items-center gap-1 text-xs text-destructive"
                        title={m.preview.error}
                        data-testid={`sync-preview-error-${m.remoteId}`}
                      >
                        <AlertCircle className="h-3.5 w-3.5" />
                        {t("syncPage.previewFailed", "Preview failed")}
                      </span>
                    )}
                    {m.preview && !m.preview.error && (
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
            {previewBatchMutation.isError && (
              // A failed preview used to leave the page silent: the spinner
              // stopped and nothing else changed.
              <span
                className="inline-flex items-center gap-1 text-destructive"
                data-testid="sync-preview-all-error"
              >
                <AlertCircle className="h-3.5 w-3.5" />
                {getErrorMessage(previewBatchMutation.error) ||
                  t("syncPage.previewFailed", "Preview failed")}
              </span>
            )}
            {executeBatchMutation.isError && (
              <span
                className="inline-flex items-center gap-1 text-destructive"
                data-testid="sync-outcome-error"
              >
                <AlertCircle className="h-3.5 w-3.5" />
                {getErrorMessage(executeBatchMutation.error) ||
                  t("syncPage.syncError", "Sync failed")}
              </span>
            )}
          </div>

          {/* What the sync actually did — never inferred from "the request resolved" */}
          {executeBatchMutation.data && (
            <SyncOutcome execution={executeBatchMutation.data} />
          )}
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

/**
 * What the sync wrote, per agent.
 *
 * The page used to render a green "Sync complete" whenever the mutation
 * resolved. `executeSyncBatch` deliberately resolves on HTTP 500 as well —
 * that status means *every* mapping failed and the body carries the reasons,
 * which are worth showing — so a sync that wrote nothing at all reported
 * success. The outcome is read from the results themselves.
 */
function SyncOutcome({ execution }: { execution: BatchSyncExecution }) {
  const { t } = useTranslation();
  const { partial, results } = execution;

  // "Nothing to write" has to mean the agent was untouched too: a run that only
  // reordered workflows writes no resource but does burn an agent version, and
  // calling that "already up to date" is wrong.
  const wrote = results.reduce(
    (sum, r) =>
      sum + (r.result?.updated ?? 0) + (r.result?.created ?? 0) + (r.result?.agentUpdated ? 1 : 0),
    0
  );
  const failedAgents = results.filter((r) => r.error || hasFailures(r.result));

  return (
    <div
      className="border-t border-border px-5 py-3 space-y-2"
      data-testid="sync-outcome"
      data-outcome={partial ? "partial" : "ok"}
    >
      <div className="flex items-center gap-1.5 text-xs font-medium">
        {partial ? (
          <>
            <AlertCircle className="h-3.5 w-3.5 text-destructive" />
            <span className="text-destructive">
              {t("syncPage.syncPartial", "Sync incomplete — some resources were not written")}
            </span>
          </>
        ) : (
          <>
            <CheckCircle className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" />
            <span className="text-emerald-600 dark:text-emerald-400">
              {wrote > 0
                ? t("syncPage.syncSuccess", "Sync complete")
                : t("syncPage.syncIdentical", "Already up to date — nothing to write")}
            </span>
          </>
        )}
      </div>

      {failedAgents.length > 0 && (
        <ul className="space-y-1 text-xs text-muted-foreground">
          {failedAgents.map((r) => (
            <li key={r.sourceAgentId} data-testid={`sync-failure-${r.sourceAgentId}`}>
              <span className="font-medium text-foreground">{r.sourceAgentId}</span>
              {": "}
              {r.error ||
                r.result?.failures
                  .map((f) => `${f.name || f.resourceType} — ${f.reason}`)
                  .join("; ")}
            </li>
          ))}
        </ul>
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
