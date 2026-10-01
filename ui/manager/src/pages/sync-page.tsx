import { useState, useMemo, useEffect, Fragment } from "react";
import { useTranslation } from "react-i18next";
import {
  RefreshCw,
  ChevronDown,
  ChevronRight,
  Loader2,
  AlertCircle,
  ArrowRightLeft,
  Sparkles,
  CheckCircle2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { SyncConfigPanel } from "@/components/agents/sync-config-panel";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
import { ActionBadge } from "@/components/shared/action-badge";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";
import { PreviewNotices } from "@/components/agents/import-steps/preview-step";
import {
  usePreviewSyncBatch,
  useExecuteSyncBatch,
} from "@/hooks/use-backup";
import { useAllAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
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
  /**
   * The operator picked "Create new" themselves. Only then is a copy forced:
   * otherwise a mapping with no target lets the backend find the agent an
   * earlier sync promoted from this source, instead of creating another one.
   */
  createNew: boolean;
  checked: boolean;
  preview: ImportPreview | null;
  /** CONFLICT rows the operator chose to overwrite; every other one is left alone. */
  overwrite: string[];
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
  const {
    data: agentPages,
    isComplete: localAgentsComplete,
    isError: localAgentsFailed,
    refetch: refetchLocalAgents,
  } = useAllAgentDescriptors();
  const localAgents = useMemo(
    () => groupAgentsByName(agentPages?.pages.flat() ?? []),
    [agentPages]
  );
  // Remote agents received before the local list finished loading. Matching
  // them against a partial list would leave every agent past the loaded pages
  // unmatched — and a sync of an unmatched agent CREATES it, a duplicate.
  const [pendingRemote, setPendingRemote] = useState<DocumentDescriptor[] | null>(null);

  const previewBatchMutation = usePreviewSyncBatch();
  const executeBatchMutation = useExecuteSyncBatch();

  function handleConnected(agents: DocumentDescriptor[]) {
    if (!localAgentsComplete) {
      // Match once every local page has arrived — see pendingRemote.
      setPendingRemote(agents);
      setMappings([]);
      setExpandedAgent(null);
      return;
    }
    setPendingRemote(null);
    matchRemoteAgents(agents);
  }

  // Deferred auto-match: runs when the local list completes after connecting.
  useEffect(() => {
    if (pendingRemote && localAgentsComplete) {
      setPendingRemote(null);
      matchRemoteAgents(pendingRemote);
    }
    // matchRemoteAgents reads localAgents, which is complete exactly when this fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingRemote, localAgentsComplete]);

  function matchRemoteAgents(agents: DocumentDescriptor[]) {
    // Auto-match: the local agent promoted from this remote one first — its
    // originId is the remote id, and it survives a rename on either side — and
    // only then by name.
    const newMappings: AgentMapping[] = agents.map((remote) => {
      const { id, version } = parseResourceUri(remote.resource);
      const localMatch =
        localAgents.find((la) => la.originId === id) ??
        localAgents.find((la) => la.name?.toLowerCase() === remote.name?.toLowerCase());
      return {
        remoteAgent: remote,
        remoteId: id,
        remoteVersion: version,
        localTargetId: localMatch?.id || null,
        autoMatched: !!localMatch,
        createNew: false,
        checked: true,
        preview: null,
        overwrite: [],
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
      createNew: m.createNew,
    }));

    // The previous run's outcome describes resources this preview is about to
    // replace; leaving it on screen reads as the result of what is about to happen.
    executeBatchMutation.reset();

    // Previews from an earlier run must not survive this one. They used to:
    // a mapping the new response did not cover (or every mapping, when the
    // request failed) kept its OLD preview, and "Sync Selected" — which is
    // enabled by any previewed selection — then synced on the strength of a
    // diff nobody had just looked at. That includes UNCHECKED mappings: one
    // unchecked before a failed run and re-checked after it re-armed Sync with
    // its old preview.
    setMappings((prev) => prev.map((m) => ({ ...m, preview: null })));

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
              if (!p) {
                return {
                  ...m,
                  preview: missingPreview(m.remoteId, noPreviewMessage),
                  overwrite: [],
                };
              }
              // With no target named, the backend previews onto the agent an earlier
              // sync promoted from this source. Adopt it, so the row shows which agent
              // will be written and the sync names the same one.
              const localTargetId =
                m.localTargetId ?? (m.createNew ? null : p.targetAgentId ?? null);
              return { ...m, preview: p, localTargetId, overwrite: [] };
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
      // Everything (null) unless a conflict is to be overwritten: a CONFLICT row is
      // written only when named, so naming it means naming the rest as well.
      selectedResources:
        m.overwrite.length > 0 && m.preview
          ? m.preview.resources
              .filter((r) => r.action !== "CONFLICT" || m.overwrite.includes(r.sourceId))
              .map((r) => r.sourceId)
          : null,
      workflowOrder: null,
      createNew: m.createNew,
    }));

    executeBatchMutation.mutate(
      { sourceUrl: syncUrl, requests, sourceAuth: syncAuth },
      {
        onSuccess: (execution) => {
          // A mapping that had no local target now has one — the agent this run
          // created. Without adopting it, the next Preview + Sync sends
          // targetAgentId: null again and creates a SECOND copy of the same agent.
          // The backend names the agent it wrote in targetAgentId — the one it
          // created, or the earlier promotion it found — and in agentUri; older
          // backends only in agentUri.
          const syncedInto = new Map<string, string>();
          for (const result of execution.results) {
            const id =
              result.targetAgentId ??
              (result.result?.agentUri ? parseResourceUri(result.result.agentUri).id : null);
            if (id) syncedInto.set(result.sourceAgentId, id);
          }
          setMappings((prev) =>
            prev.map((m) => {
              const adopted = m.localTargetId ? undefined : syncedInto.get(m.remoteId);
              return {
                ...m,
                localTargetId: m.localTargetId ?? adopted ?? null,
                // The copy exists now; syncing it again must update it, not make another.
                createNew: adopted ? false : m.createNew,
                preview: null,
                overwrite: [],
              };
            })
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
        {localAgentsFailed && (
          <div
            className="mt-4 flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive"
            role="alert"
            data-testid="sync-local-agents-error"
          >
            <span>
              {t(
                "syncPage.localAgentsError",
                "The local agent list could not be loaded completely, so agents cannot be matched by name. Syncing now would create duplicates."
              )}
            </span>
            <Button variant="outline" size="sm" onClick={() => void refetchLocalAgents()}>
              {t("common.retry")}
            </Button>
          </div>
        )}
        {pendingRemote && !localAgentsFailed && (
          <p className="mt-4 text-sm text-muted-foreground" role="status" data-testid="sync-local-agents-loading">
            {t("syncPage.loadingLocalAgents", "Loading all local agents before matching…")}
          </p>
        )}
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
                          createNew: !e.target.value,
                          preview: null,
                          overwrite: [],
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
                  <AgentSyncDetail
                    preview={m.preview}
                    overwrite={m.overwrite}
                    onToggleOverwrite={(sourceId) =>
                      updateMapping(idx, {
                        overwrite: m.overwrite.includes(sourceId)
                          ? m.overwrite.filter((id) => id !== sourceId)
                          : [...m.overwrite, sourceId],
                      })
                    }
                  />
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
            <SyncOutcome
              execution={executeBatchMutation.data}
              names={new Map(mappings.map((m) => [m.remoteId, m.remoteAgent.name || m.remoteId]))}
            />
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
function SyncOutcome({
  execution,
  names,
}: {
  execution: BatchSyncExecution;
  names: Map<string, string>;
}) {
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
            <CheckCircle2 className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" />
            <span className="text-emerald-600 dark:text-emerald-400">
              {wrote > 0
                ? t("syncPage.syncSuccess", "Sync complete")
                : t("syncPage.syncIdentical", "Already up to date — nothing to write")}
            </span>
          </>
        )}
      </div>

      {/* What each agent got — a single "Sync complete" said nothing about it. */}
      <ul className="space-y-0.5 text-xs text-muted-foreground" data-testid="sync-outcome-counts">
        {results
          .filter((r) => r.result)
          .map((r) => (
            <li key={r.sourceAgentId}>
              <span className="font-medium text-foreground">
                {names.get(r.sourceAgentId) ?? r.sourceAgentId}
              </span>
              {": "}
              {t("syncPage.outcomeCounts", "{{updated}} updated · {{created}} created · {{skipped}} unchanged", {
                updated: r.result?.updated ?? 0,
                created: r.result?.created ?? 0,
                skipped: r.result?.skipped ?? 0,
              })}
            </li>
          ))}
      </ul>

      {wrote > 0 && (
        <p className="text-xs text-muted-foreground" data-testid="sync-redeploy-hint">
          {t(
            "syncPage.redeployHint",
            "Synced changes are saved as new versions. What is running keeps running — deploy the new version to make it live."
          )}
        </p>
      )}

      {failedAgents.length > 0 && (
        <ul className="space-y-1 text-xs text-muted-foreground">
          {failedAgents.map((r) => (
            <li key={r.sourceAgentId} data-testid={`sync-failure-${r.sourceAgentId}`}>
              <span className="font-medium text-foreground">{names.get(r.sourceAgentId) ?? r.sourceAgentId}</span>
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
function AgentSyncDetail({
  preview,
  overwrite,
  onToggleOverwrite,
}: {
  preview: ImportPreview;
  overwrite: string[];
  onToggleOverwrite: (sourceId: string) => void;
}) {
  const { t } = useTranslation();
  const [expandedRow, setExpandedRow] = useState<string | null>(null);

  return (
    <div className="space-y-2 bg-secondary/20 px-5 pb-4">
      <PreviewNotices preview={preview} />
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
                (r.action === "UPDATE" || r.action === "CONFLICT" || r.action === "REMOVE") &&
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
                      <div className="flex items-center gap-2">
                        <ActionBadge action={r.action} />
                        {r.action === "CONFLICT" && (
                          <label className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                            <input
                              type="checkbox"
                              checked={overwrite.includes(r.sourceId)}
                              onChange={() => onToggleOverwrite(r.sourceId)}
                              className="accent-primary"
                              data-testid={`sync-overwrite-${r.sourceId}`}
                            />
                            {t("syncPage.overwrite", "Overwrite local change")}
                          </label>
                        )}
                      </div>
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
