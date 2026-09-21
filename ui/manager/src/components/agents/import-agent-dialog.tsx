import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X, ArrowLeft, ArrowRight, AlertTriangle } from "lucide-react";
import {
  useImportAgent,
  usePreviewImport,
  useImportAgentMerge,
  usePreviewUpgrade,
  useImportUpgrade,
  useExecuteSync,
  usePreviewSync,
} from "@/hooks/use-backup";
import type { ImportPreview, DocumentDescriptor, SyncExecution } from "@/lib/api/backup";
import { Button } from "@/components/ui/button";
import { useInfiniteAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { SyncConfigPanel } from "@/components/agents/sync-config-panel";
import { parseResourceUri } from "@/lib/api/backup";
import { UploadStep, StrategyStep, PreviewStep } from "@/components/agents/import-steps";

interface ImportAgentDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * `outcome` is the step a partial result lands on.
 *
 * EDDI answers 201 wrote-something, 200 already-identical and 207
 * some-resources-failed — all 2xx. Closing the dialog on every one of them, as
 * this did, reported a half-applied sync exactly like a clean one, and the only
 * record of what was left behind was the response body nobody read.
 */
type Step = "upload" | "strategy" | "target" | "preview" | "importing" | "outcome";
type Strategy = "create" | "merge" | "upgrade" | "sync";

export function ImportAgentDialog({ open, onClose, onSuccess }: ImportAgentDialogProps) {
  const { t } = useTranslation();

  const [step, setStep] = useState<Step>("upload");
  const [file, setFile] = useState<File | null>(null);
  const [strategy, setStrategy] = useState<Strategy>("create");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [expandedDiff, setExpandedDiff] = useState<string | null>(null);
  const [workflowOrder, setWorkflowOrder] = useState<string[]>([]);
  const [dragging, setDragging] = useState(false);
  const [outcome, setOutcome] = useState<SyncExecution | null>(null);
  const [schedulesSkipped, setSchedulesSkipped] = useState<number | null>(null);

  // Target state for upgrade
  const [targetAgentId, setTargetAgentId] = useState<string | null>(null);

  // Target state for sync
  const [syncUrl, setSyncUrl] = useState("");
  const [syncAuth, setSyncAuth] = useState("");
  const [remoteAgents, setRemoteAgents] = useState<DocumentDescriptor[]>([]);
  const [sourceAgent, setSourceAgent] = useState<string | null>(null);
  const [sourceVersion, setSourceVersion] = useState<number | null>(null);
  const [syncTargetId, setSyncTargetId] = useState<string | null>(null);

  const importMutation = useImportAgent();
  const previewMutation = usePreviewImport();
  const mergeMutation = useImportAgentMerge();
  const previewUpgradeMutation = usePreviewUpgrade();
  const importUpgradeMutation = useImportUpgrade();
  const previewSyncMutation = usePreviewSync();
  const executeSyncMutation = useExecuteSync();

  const reset = useCallback(() => {
    setStep("upload");
    setFile(null);
    setStrategy("create");
    setPreview(null);
    setSelected(new Set());
    setError(null);
    setExpandedDiff(null);
    setWorkflowOrder([]);
    setDragging(false);
    setOutcome(null);
    setSchedulesSkipped(null);
    setTargetAgentId(null);
    setSyncUrl("");
    setSyncAuth("");
    setRemoteAgents([]);
    setSourceAgent(null);
    setSourceVersion(null);
    setSyncTargetId(null);
  }, []);

  function handleClose() {
    reset();
    onClose();
  }

  function handleFileAccepted(f: File) {
    setFile(f);
    setStep("strategy");
  }

  function handleStrategyNext() {
    setError(null);
    if (strategy === "create" && file) {
      // Direct import
      setStep("importing");
      importMutation.mutate(file, {
        onSuccess: () => { onSuccess(); handleClose(); },
        onError: (err) => { setError(err.message); setStep("strategy"); },
      });
    } else if (strategy === "merge" && file) {
      // Merge — get preview
      previewMutation.mutate(file, {
        onSuccess: (data) => {
          setPreview(data);
          const allIds = new Set(data.resources.map((r) => r.sourceId));
          setSelected(allIds);
          setStep("preview");
        },
        onError: (err) => setError(err.message),
      });
    } else if (strategy === "upgrade" || strategy === "sync") {
      setStep("target");
    }
  }

  function handleTargetNext() {
    setError(null);

    if (strategy === "upgrade" && file && targetAgentId) {
      previewUpgradeMutation.mutate(
        { file, targetAgentId },
        {
          onSuccess: (data) => {
            setPreview(data);
            const allIds = new Set(data.resources.map((r) => r.sourceId));
            setSelected(allIds);
            // Initialize workflow order from CREATE workflow resources
            const wfIds = data.resources
              .filter((r) => r.resourceType === "workflow" && r.action === "CREATE")
              .sort((a, b) => a.workflowIndex - b.workflowIndex)
              .map((r) => r.sourceId);
            setWorkflowOrder(wfIds);
            setStep("preview");
          },
          onError: (err) => setError(err.message),
        }
      );
    } else if (strategy === "sync" && sourceAgent && syncUrl) {
      previewSyncMutation.mutate(
        {
          sourceUrl: syncUrl,
          sourceAgentId: sourceAgent,
          sourceVersion,
          targetAgentId: syncTargetId,
          sourceAuth: syncAuth,
        },
        {
          onSuccess: (data) => {
            setPreview(data);
            const allIds = new Set(data.resources.map((r) => r.sourceId));
            setSelected(allIds);
            setStep("preview");
          },
          onError: (err) => setError(err.message),
        }
      );
    }
  }

  function handleExecuteImport() {
    setError(null);
    setStep("importing");

    const selectedIds = Array.from(selected);

    /**
     * The data changed either way, so the list behind the dialog is refreshed
     * on every outcome. What changes is whether the dialog may close: a partial
     * result has to stay on screen, because it names resources the operator now
     * has to go and fix by hand and nothing else in the product records them.
     */
    function settle(execution: SyncExecution) {
      onSuccess();
      if (execution.outcome === "partial") {
        setOutcome(execution);
        setStep("outcome");
        return;
      }
      handleClose();
    }

    if (strategy === "merge" && file) {
      mergeMutation.mutate(
        { file, selectedSourceIds: selectedIds },
        {
          onSuccess: (result) => {
            onSuccess();
            if (result.schedulesSkipped !== null) {
              // `selectedResources` is one flat list over every preview row, so
              // selecting extensions alone drops every schedule in the archive.
              // The header is the only place that is stated.
              setSchedulesSkipped(result.schedulesSkipped);
              setStep("outcome");
              return;
            }
            handleClose();
          },
          onError: (err) => { setError(err.message); setStep("preview"); },
        }
      );
    } else if (strategy === "upgrade" && file && targetAgentId) {
      importUpgradeMutation.mutate(
        { file, targetAgentId, selectedSourceIds: selectedIds, workflowOrder },
        {
          onSuccess: settle,
          onError: (err) => { setError(err.message); setStep("preview"); },
        }
      );
    } else if (strategy === "sync" && sourceAgent && syncUrl) {
      executeSyncMutation.mutate(
        {
          sourceUrl: syncUrl,
          sourceAgentId: sourceAgent,
          sourceVersion,
          targetAgentId: syncTargetId,
          selectedResources: selectedIds,
          workflowOrder: workflowOrder.length > 0 ? workflowOrder : null,
          sourceAuth: syncAuth,
        },
        {
          onSuccess: settle,
          onError: (err) => { setError(err.message); setStep("preview"); },
        }
      );
    }
  }

  function toggleResource(sourceId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(sourceId)) next.delete(sourceId);
      else next.add(sourceId);
      return next;
    });
  }

  function toggleAll() {
    if (!preview) return;
    if (selected.size === preview.resources.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(preview.resources.map((r) => r.sourceId)));
    }
  }

  function moveWorkflow(id: string, dir: -1 | 1) {
    setWorkflowOrder((prev) => {
      const idx = prev.indexOf(id);
      if (idx < 0) return prev;
      const newIdx = idx + dir;
      if (newIdx < 0 || newIdx >= prev.length) return prev;
      const next = [...prev];
      [next[idx], next[newIdx]] = [next[newIdx]!, next[idx]!];
      return next;
    });
  }

  if (!open) return null;

  const isLoading =
    importMutation.isPending ||
    previewMutation.isPending ||
    mergeMutation.isPending ||
    previewUpgradeMutation.isPending ||
    importUpgradeMutation.isPending ||
    previewSyncMutation.isPending ||
    executeSyncMutation.isPending;

  const canTargetNext =
    (strategy === "upgrade" && !!targetAgentId) ||
    (strategy === "sync" && !!sourceAgent);

  return (
    <>
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={!isLoading ? handleClose : undefined}
      />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-2xl rounded-xl border bg-card p-6 shadow-2xl max-h-[85vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
          data-testid="import-agent-dialog"
        >
          {/* Header */}
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-lg font-semibold text-foreground">
              {t("importDialog.title", "Import Agent")}
            </h2>
            <button
              onClick={handleClose}
              disabled={isLoading}
              className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-50"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* === Step: Upload === */}
          {step === "upload" && (
            <UploadStep
              dragging={dragging}
              onDragging={setDragging}
              onFile={handleFileAccepted}
            />
          )}

          {/* === Step: Strategy === */}
          {step === "strategy" && file && (
            <StrategyStep
              file={file}
              strategy={strategy}
              onStrategyChange={setStrategy}
              onBack={() => { setFile(null); setStep("upload"); }}
              onNext={handleStrategyNext}
              isLoading={isLoading}
              isPreviewing={previewMutation.isPending}
              error={error}
            />
          )}

          {/* === Step: Target === */}
          {step === "target" && (
            <TargetStep
              strategy={strategy}
              targetAgentId={targetAgentId}
              onSelectTarget={setTargetAgentId}
              syncUrl={syncUrl}
              syncAuth={syncAuth}
              remoteAgents={remoteAgents}
              sourceAgent={sourceAgent}
              syncTargetId={syncTargetId}
              onSyncUrlChange={setSyncUrl}
              onSyncAuthChange={setSyncAuth}
              onRemoteAgents={setRemoteAgents}
              onSourceAgent={(id, version) => { setSourceAgent(id); setSourceVersion(version); }}
              onSyncTarget={setSyncTargetId}
              error={error}
              isLoading={isLoading}
              isPreviewing={previewUpgradeMutation.isPending || previewSyncMutation.isPending}
              canNext={canTargetNext}
              onBack={() => setStep("strategy")}
              onNext={handleTargetNext}
            />
          )}

          {/* === Step: Preview === */}
          {step === "preview" && preview && (
            <PreviewStep
              preview={preview}
              strategy={strategy}
              selected={selected}
              expandedDiff={expandedDiff}
              workflowOrder={workflowOrder}
              isLoading={isLoading}
              error={error}
              onToggleResource={toggleResource}
              onToggleAll={toggleAll}
              onExpandDiff={setExpandedDiff}
              onMoveWorkflow={moveWorkflow}
              onBack={() => setStep(strategy === "merge" ? "strategy" : "target")}
              onExecute={handleExecuteImport}
            />
          )}

          {/* === Step: Importing === */}
          {step === "importing" && (
            <div className="flex-1 flex flex-col items-center justify-center gap-4 py-8">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted-foreground border-t-primary" />
              <p className="text-sm text-muted-foreground">
                {t("importDialog.importing", "Importing agent...")}
              </p>
            </div>
          )}

          {/* === Step: Outcome ===
              Only reached when something needs saying. A clean import still
              closes straight away. */}
          {step === "outcome" && (
            <div className="flex-1 space-y-4 overflow-y-auto py-4" data-testid="import-outcome">
              {outcome?.outcome === "partial" && (
                <>
                  <div className="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3">
                    <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
                    <div className="space-y-1">
                      <p className="text-sm font-medium text-destructive">
                        {t("importDialog.partialTitle", "Some resources were not imported")}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {t(
                          "importDialog.partialBody",
                          "The rest of the agent was written. These resources were left as they were and need attention.",
                        )}
                      </p>
                    </div>
                  </div>
                  <ul className="space-y-2" data-testid="import-failures">
                    {(outcome.result?.failures ?? []).map((failure) => (
                      <li
                        key={`${failure.resourceType}:${failure.sourceId}`}
                        className="rounded-md border border-border bg-card p-2.5"
                      >
                        <p className="text-xs font-medium text-foreground">
                          {failure.name || failure.sourceId}{" "}
                          <span className="font-normal text-muted-foreground">
                            ({failure.resourceType})
                          </span>
                        </p>
                        <p className="mt-0.5 text-xs text-destructive">{failure.reason}</p>
                      </li>
                    ))}
                  </ul>
                  <p className="text-xs text-muted-foreground" data-testid="import-counts">
                    {t("importDialog.partialCounts", {
                      created: outcome.result?.created ?? 0,
                      updated: outcome.result?.updated ?? 0,
                      failed: outcome.result?.failures?.length ?? 0,
                      defaultValue:
                        "{{created}} created, {{updated}} updated, {{failed}} failed.",
                    })}
                  </p>
                </>
              )}

              {schedulesSkipped !== null && (
                <div
                  className="flex items-start gap-2 rounded-lg border border-warning/30 bg-warning/5 p-3"
                  data-testid="import-schedules-skipped"
                >
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
                  <p className="text-xs text-foreground">
                    {t("importDialog.schedulesSkipped", {
                      count: schedulesSkipped,
                      defaultValue:
                        "{{count}} schedule(s) in the archive were not imported, because the selection did not include them.",
                    })}
                  </p>
                </div>
              )}

              <div className="flex justify-end">
                <Button onClick={handleClose} data-testid="import-outcome-close">
                  {t("common.close", "Close")}
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/* ─── Target Step (kept inline — tightly coupled to both upgrade and sync state) ─── */

function TargetStep({
  strategy,
  targetAgentId,
  onSelectTarget,
  syncUrl,
  syncAuth,
  remoteAgents,
  sourceAgent,
  syncTargetId,
  onSyncUrlChange,
  onSyncAuthChange,
  onRemoteAgents,
  onSourceAgent,
  onSyncTarget,
  error,
  isLoading,
  isPreviewing,
  canNext,
  onBack,
  onNext,
}: {
  strategy: Strategy;
  targetAgentId: string | null;
  onSelectTarget: (id: string) => void;
  syncUrl: string;
  syncAuth: string;
  remoteAgents: DocumentDescriptor[];
  sourceAgent: string | null;
  syncTargetId: string | null;
  onSyncUrlChange: (url: string) => void;
  onSyncAuthChange: (auth: string) => void;
  onRemoteAgents: (agents: DocumentDescriptor[]) => void;
  onSourceAgent: (id: string, version: number | null) => void;
  onSyncTarget: (id: string | null) => void;
  error: string | null;
  isLoading: boolean;
  isPreviewing: boolean;
  canNext: boolean;
  onBack: () => void;
  onNext: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div className="flex-1 space-y-4">
      {strategy === "upgrade" && (
        <UpgradeTargetPicker
          targetAgentId={targetAgentId}
          onSelect={onSelectTarget}
        />
      )}

      {strategy === "sync" && (
        <SyncTargetPicker
          syncUrl={syncUrl}
          syncAuth={syncAuth}
          remoteAgents={remoteAgents}
          sourceAgent={sourceAgent}
          syncTargetId={syncTargetId}
          onUrlChange={onSyncUrlChange}
          onAuthChange={onSyncAuthChange}
          onRemoteAgents={onRemoteAgents}
          onSourceAgent={onSourceAgent}
          onSyncTarget={onSyncTarget}
        />
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}

      <div className="flex justify-between pt-2">
        <Button variant="ghost" onClick={onBack} disabled={isLoading}>
          <ArrowLeft className="h-4 w-4" />
          {t("common.back", "Back")}
        </Button>
        <Button
          onClick={onNext}
          disabled={isLoading || !canNext}
          data-testid="import-target-next"
        >
          {isPreviewing
            ? t("common.loading", "Loading...")
            : t("importDialog.previewChanges", "Preview Changes")}
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

/* ─── Target Picker Sub-components ─── */

function UpgradeTargetPicker({
  targetAgentId,
  onSelect,
}: {
  targetAgentId: string | null;
  onSelect: (id: string) => void;
}) {
  const { t } = useTranslation();
  const { data } = useInfiniteAgentDescriptors();
  const agents = groupAgentsByName(data?.pages.flat() ?? []);

  return (
    <div className="space-y-2">
      <label className="text-sm font-medium text-foreground">
        {t("importDialog.selectTargetAgent", "Select target agent")}
      </label>
      <p className="text-xs text-muted-foreground">
        {t("importDialog.upgradeTargetHint", "The imported resources will be structurally matched against this agent.")}
      </p>
      <select
        value={targetAgentId || ""}
        onChange={(e) => onSelect(e.target.value)}
        className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        data-testid="upgrade-target-select"
      >
        <option value="">{t("importDialog.chooseAgent", "— Choose an agent —")}</option>
        {agents.map((a) => (
          <option key={a.id} value={a.id}>
            {a.name || a.id} (v{a.version})
          </option>
        ))}
      </select>
    </div>
  );
}

function SyncTargetPicker({
  syncUrl,
  syncAuth,
  remoteAgents,
  sourceAgent,
  syncTargetId,
  onUrlChange,
  onAuthChange,
  onRemoteAgents,
  onSourceAgent,
  onSyncTarget,
}: {
  syncUrl: string;
  syncAuth: string;
  remoteAgents: DocumentDescriptor[];
  sourceAgent: string | null;
  syncTargetId: string | null;
  onUrlChange: (url: string) => void;
  onAuthChange: (auth: string) => void;
  onRemoteAgents: (agents: DocumentDescriptor[]) => void;
  onSourceAgent: (id: string, version: number | null) => void;
  onSyncTarget: (id: string | null) => void;
}) {
  const { t } = useTranslation();
  const { data } = useInfiniteAgentDescriptors();
  const localAgents = groupAgentsByName(data?.pages.flat() ?? []);

  return (
    <div className="space-y-4">
      <SyncConfigPanel
        url={syncUrl}
        auth={syncAuth}
        onUrlChange={onUrlChange}
        onAuthChange={onAuthChange}
        onConnected={onRemoteAgents}
      />

      {remoteAgents.length > 0 && (
        <>
          <div className="space-y-2">
            <label className="text-sm font-medium text-foreground">
              {t("importDialog.sourceAgent", "Source agent (remote)")}
            </label>
            <select
              value={sourceAgent || ""}
              onChange={(e) => {
                const remote = remoteAgents.find((a) => {
                  const { id } = parseResourceUri(a.resource);
                  return id === e.target.value;
                });
                if (remote) {
                  const { id, version } = parseResourceUri(remote.resource);
                  onSourceAgent(id, version);
                }
              }}
              className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="sync-source-select"
            >
              <option value="">{t("importDialog.chooseAgent", "— Choose an agent —")}</option>
              {remoteAgents.map((a) => {
                const { id } = parseResourceUri(a.resource);
                return (
                  <option key={id} value={id}>
                    {a.name || id}
                  </option>
                );
              })}
            </select>
          </div>

          <div className="space-y-2">
            <label className="text-sm font-medium text-foreground">
              {t("importDialog.targetAgent", "Target agent (local)")}
            </label>
            <select
              value={syncTargetId || ""}
              onChange={(e) => onSyncTarget(e.target.value || null)}
              className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="sync-target-select"
            >
              <option value="">{t("importDialog.createNewTarget", "Create new agent")}</option>
              {localAgents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name || a.id} (v{a.version})
                </option>
              ))}
            </select>
          </div>
        </>
      )}
    </div>
  );
}
