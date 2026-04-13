import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X, ArrowLeft, ArrowRight } from "lucide-react";
import {
  useImportAgent,
  usePreviewImport,
  useImportAgentMerge,
  usePreviewUpgrade,
  useImportUpgrade,
  useExecuteSync,
  usePreviewSync,
} from "@/hooks/use-backup";
import type { ImportPreview, DocumentDescriptor } from "@/lib/api/backup";
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

type Step = "upload" | "strategy" | "target" | "preview" | "importing";
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

    if (strategy === "merge" && file) {
      mergeMutation.mutate(
        { file, selectedSourceIds: selectedIds },
        {
          onSuccess: () => { onSuccess(); handleClose(); },
          onError: (err) => { setError(err.message); setStep("preview"); },
        }
      );
    } else if (strategy === "upgrade" && file && targetAgentId) {
      importUpgradeMutation.mutate(
        { file, targetAgentId, selectedSourceIds: selectedIds, workflowOrder },
        {
          onSuccess: () => { onSuccess(); handleClose(); },
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
          onSuccess: () => { onSuccess(); handleClose(); },
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
