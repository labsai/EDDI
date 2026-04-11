import { useState, useRef, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  X,
  FileArchive,
  RefreshCw,
  Plus,
  Check,
  ArrowRight,
  ArrowLeft,
  ArrowUp,
  ArrowDown,
  ChevronDown,
  ChevronRight,
  Upload,
  Globe,
} from "lucide-react";
import {
  useImportAgent,
  usePreviewImport,
  useImportAgentMerge,
  usePreviewUpgrade,
  useImportUpgrade,
  useExecuteSync,
  usePreviewSync,
} from "@/hooks/use-backup";
import { parseResourceUri } from "@/lib/api/backup";
import type { ImportPreview, DocumentDescriptor } from "@/lib/api/backup";
import { Button } from "@/components/ui/button";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
import { ActionBadge } from "@/components/shared/action-badge";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";
import { useInfiniteAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { SyncConfigPanel } from "@/components/agents/sync-config-panel";

interface ImportAgentDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

type Step = "upload" | "strategy" | "target" | "preview" | "importing";
type Strategy = "create" | "merge" | "upgrade" | "sync";

export function ImportAgentDialog({ open, onClose, onSuccess }: ImportAgentDialogProps) {
  const { t } = useTranslation();
  const fileInputRef = useRef<HTMLInputElement>(null);

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

  function handleFileDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragging(false);
    const droppedFile = e.dataTransfer.files[0];
    if (droppedFile?.name.endsWith(".zip")) {
      setFile(droppedFile);
      setStep("strategy");
    }
  }

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    if (f?.name.endsWith(".zip")) {
      setFile(f);
      setStep("strategy");
    }
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
            <div
              className={`flex-1 flex flex-col items-center justify-center gap-4 rounded-lg border-2 border-dashed p-8 transition-colors cursor-pointer ${
                dragging
                  ? "border-primary bg-primary/5 scale-[1.01]"
                  : "border-border hover:border-primary/50"
              }`}
              onDragOver={(e) => e.preventDefault()}
              onDragEnter={() => setDragging(true)}
              onDragLeave={() => setDragging(false)}
              onDrop={handleFileDrop}
              onClick={() => fileInputRef.current?.click()}
              data-testid="import-drop-zone"
            >
              <FileArchive className="h-12 w-12 text-muted-foreground" />
              <div className="text-center">
                <p className="text-sm font-medium text-foreground">
                  {t("importDialog.dropZone", "Drop a .zip file here or click to browse")}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {t("importDialog.dropZoneHint", "Exported agent archive (.zip)")}
                </p>
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".zip"
                onChange={handleFileSelect}
                className="hidden"
                data-testid="import-file-input"
              />
            </div>
          )}

          {/* === Step: Strategy === */}
          {step === "strategy" && file && (
            <div className="flex-1 space-y-4">
              <div className="flex items-center gap-3 rounded-lg bg-secondary/50 p-3">
                <FileArchive className="h-5 w-5 text-primary shrink-0" />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground truncate">{file.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {(file.size / 1024).toFixed(1)} KB
                  </p>
                </div>
              </div>

              <fieldset className="space-y-2">
                <legend className="text-sm font-medium text-foreground mb-2">
                  {t("importDialog.strategyLabel", "Import Strategy")}
                </legend>

                <StrategyOption
                  value="create"
                  current={strategy}
                  onChange={setStrategy}
                  icon={<Plus className="h-4 w-4 text-emerald-500" />}
                  label={t("importDialog.createNew", "Create as new agent")}
                  desc={t("importDialog.createNewDesc", "Creates a fresh copy with new IDs. Best for first-time import.")}
                  testId="strategy-create"
                />

                <StrategyOption
                  value="merge"
                  current={strategy}
                  onChange={setStrategy}
                  icon={<RefreshCw className="h-4 w-4 text-blue-500" />}
                  label={t("importDialog.mergeSync", "Merge / sync with existing")}
                  desc={t("importDialog.mergeSyncDesc", "Updates existing resources if previously imported. Creates new ones if not found.")}
                  testId="strategy-merge"
                />

                <StrategyOption
                  value="upgrade"
                  current={strategy}
                  onChange={setStrategy}
                  icon={<Upload className="h-4 w-4 text-amber-500" />}
                  label={t("importDialog.upgradeExisting", "Upgrade existing agent")}
                  desc={t("importDialog.upgradeDesc", "Match resources structurally against a target agent. Best for promoting across environments.")}
                  testId="strategy-upgrade"
                />

                <StrategyOption
                  value="sync"
                  current={strategy}
                  onChange={setStrategy}
                  icon={<Globe className="h-4 w-4 text-violet-500" />}
                  label={t("importDialog.syncRemote", "Sync from remote instance")}
                  desc={t("importDialog.syncDesc", "Connect to another EDDI instance and sync a specific agent live.")}
                  testId="strategy-sync"
                />
              </fieldset>

              {error && <p className="text-sm text-destructive">{error}</p>}

              <div className="flex justify-between pt-2">
                <Button variant="ghost" onClick={() => { setFile(null); setStep("upload"); }} disabled={isLoading}>
                  <ArrowLeft className="h-4 w-4" />
                  {t("common.back", "Back")}
                </Button>
                <Button onClick={handleStrategyNext} disabled={isLoading} data-testid="import-confirm-strategy">
                  {previewMutation.isPending
                    ? t("common.loading", "Loading...")
                    : strategy === "create"
                      ? t("importDialog.importNow", "Import Now")
                      : strategy === "merge"
                        ? t("importDialog.previewChanges", "Preview Changes")
                        : t("common.next", "Next")}
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}

          {/* === Step: Target === */}
          {step === "target" && (
            <div className="flex-1 space-y-4">
              {strategy === "upgrade" && (
                <UpgradeTargetPicker
                  targetAgentId={targetAgentId}
                  onSelect={setTargetAgentId}
                />
              )}

              {strategy === "sync" && (
                <SyncTargetPicker
                  syncUrl={syncUrl}
                  syncAuth={syncAuth}
                  remoteAgents={remoteAgents}
                  sourceAgent={sourceAgent}
                  syncTargetId={syncTargetId}
                  onUrlChange={setSyncUrl}
                  onAuthChange={setSyncAuth}
                  onRemoteAgents={setRemoteAgents}
                  onSourceAgent={(id, version) => { setSourceAgent(id); setSourceVersion(version); }}
                  onSyncTarget={setSyncTargetId}
                />
              )}

              {error && <p className="text-sm text-destructive">{error}</p>}

              <div className="flex justify-between pt-2">
                <Button variant="ghost" onClick={() => setStep("strategy")} disabled={isLoading}>
                  <ArrowLeft className="h-4 w-4" />
                  {t("common.back", "Back")}
                </Button>
                <Button
                  onClick={handleTargetNext}
                  disabled={isLoading || !canTargetNext}
                  data-testid="import-target-next"
                >
                  {(previewUpgradeMutation.isPending || previewSyncMutation.isPending)
                    ? t("common.loading", "Loading...")
                    : t("importDialog.previewChanges", "Preview Changes")}
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}

          {/* === Step: Preview === */}
          {step === "preview" && preview && (
            <div className="flex-1 flex flex-col space-y-4 min-h-0">
              <div className="flex items-center gap-2">
                <FileArchive className="h-5 w-5 text-primary shrink-0" />
                <p className="text-sm font-semibold text-foreground">
                  {preview.sourceAgentName || preview.sourceAgentId || "Agent"}
                  {preview.targetAgentName && (
                    <span className="text-muted-foreground font-normal">
                      {" → "}
                      {preview.targetAgentName}
                    </span>
                  )}
                </p>
              </div>

              {/* Resource table */}
              <div className="flex-1 overflow-auto rounded-lg border min-h-0">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-secondary/80 backdrop-blur-sm">
                    <tr>
                      <th className="px-3 py-2 text-start w-8">
                        <input
                          type="checkbox"
                          checked={selected.size === preview.resources.length}
                          onChange={toggleAll}
                          className="accent-primary"
                        />
                      </th>
                      <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                        {t("importDialog.resource", "Resource")}
                      </th>
                      <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                        {t("importDialog.type", "Type")}
                      </th>
                      {(strategy === "upgrade" || strategy === "sync") && (
                        <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                          {t("importDialog.match", "Match")}
                        </th>
                      )}
                      <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                        {t("importDialog.action", "Action")}
                      </th>
                      {(strategy === "upgrade" || strategy === "sync") && (
                        <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase w-8" />
                      )}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {preview.resources.map((r) => (
                      <PreviewRow
                        key={r.sourceId}
                        resource={r}
                        checked={selected.has(r.sourceId)}
                        onToggle={() => toggleResource(r.sourceId)}
                        showMatch={strategy === "upgrade" || strategy === "sync"}
                        showDiff={strategy === "upgrade" || strategy === "sync"}
                        expanded={expandedDiff === r.sourceId}
                        onExpand={() =>
                          setExpandedDiff(expandedDiff === r.sourceId ? null : r.sourceId)
                        }
                        workflowOrder={workflowOrder}
                        onMoveWorkflow={moveWorkflow}
                      />
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Summary */}
              <div className="flex gap-3 text-xs text-muted-foreground">
                <span>
                  {preview.resources.filter((r) => r.action === "CREATE").length}{" "}
                  {t("importDialog.new", "new")}
                </span>
                <span>
                  {preview.resources.filter((r) => r.action === "UPDATE").length}{" "}
                  {t("importDialog.updated", "updated")}
                </span>
                <span>
                  {preview.resources.filter((r) => r.action === "SKIP").length}{" "}
                  {t("importDialog.unchanged", "unchanged")}
                </span>
                <span>
                  {selected.size} {t("importDialog.selected", "selected")}
                </span>
              </div>

              {error && <p className="text-sm text-destructive">{error}</p>}

              <div className="flex justify-between pt-2">
                <Button
                  variant="ghost"
                  onClick={() =>
                    setStep(strategy === "merge" ? "strategy" : "target")
                  }
                  disabled={isLoading}
                >
                  <ArrowLeft className="h-4 w-4" />
                  {t("common.back", "Back")}
                </Button>
                <Button
                  onClick={handleExecuteImport}
                  disabled={isLoading || selected.size === 0}
                  data-testid="import-confirm-merge"
                >
                  {strategy === "sync"
                    ? t("importDialog.syncNow", "Sync Now")
                    : strategy === "upgrade"
                      ? t("importDialog.upgradeNow", "Upgrade Now")
                      : t("importDialog.mergeNow", "Import Selected")}
                  <Check className="h-4 w-4" />
                </Button>
              </div>
            </div>
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

/* ─── Sub-components ─── */

function StrategyOption({
  value,
  current,
  onChange,
  icon,
  label,
  desc,
  testId,
}: {
  value: Strategy;
  current: Strategy;
  onChange: (s: Strategy) => void;
  icon: React.ReactNode;
  label: string;
  desc: string;
  testId: string;
}) {
  return (
    <label
      className={`flex items-start gap-3 rounded-lg border p-3 cursor-pointer transition-colors ${
        current === value
          ? "border-primary bg-primary/5"
          : "border-border hover:border-primary/30"
      }`}
      data-testid={testId}
    >
      <input
        type="radio"
        name="strategy"
        value={value}
        checked={current === value}
        onChange={() => onChange(value)}
        className="mt-0.5 accent-primary"
      />
      <div>
        <div className="flex items-center gap-1.5">
          {icon}
          <span className="text-sm font-medium text-foreground">{label}</span>
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">{desc}</p>
      </div>
    </label>
  );
}

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

function MatchBadge({ strategy }: { strategy: string | null }) {
  if (!strategy) return null;
  const labels: Record<string, string> = {
    position: "pos",
    type: "type",
    name: "name",
    originId: "ID",
  };
  return (
    <span className="inline-flex rounded-full bg-secondary px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
      {labels[strategy] || strategy}
    </span>
  );
}

function PreviewRow({
  resource,
  checked,
  onToggle,
  showMatch,
  showDiff,
  expanded,
  onExpand,
  workflowOrder,
  onMoveWorkflow,
}: {
  resource: ImportPreview["resources"][0];
  checked: boolean;
  onToggle: () => void;
  showMatch: boolean;
  showDiff: boolean;
  expanded: boolean;
  onExpand: () => void;
  workflowOrder: string[];
  onMoveWorkflow: (id: string, dir: -1 | 1) => void;
}) {
  const hasDiff = resource.action === "UPDATE" && (resource.sourceContent || resource.targetContent);
  const isCreateWorkflow = resource.resourceType === "workflow" && resource.action === "CREATE";
  const wfIdx = workflowOrder.indexOf(resource.sourceId);

  return (
    <>
      <tr className={`transition-colors ${checked ? "bg-primary/5" : ""}`}>
        <td className="px-3 py-2">
          <input
            type="checkbox"
            checked={checked}
            onChange={onToggle}
            className="accent-primary"
          />
        </td>
        <td className="px-3 py-2 font-medium text-foreground">
          <div className="flex items-center gap-1.5">
            {resource.name || resource.sourceId.substring(0, 12)}
            {isCreateWorkflow && wfIdx >= 0 && (
              <span className="flex items-center gap-0.5 ms-1">
                <button
                  onClick={() => onMoveWorkflow(resource.sourceId, -1)}
                  disabled={wfIdx === 0}
                  className="rounded p-0.5 text-muted-foreground hover:text-foreground disabled:opacity-30"
                  title="Move up"
                >
                  <ArrowUp className="h-3 w-3" />
                </button>
                <button
                  onClick={() => onMoveWorkflow(resource.sourceId, 1)}
                  disabled={wfIdx === workflowOrder.length - 1}
                  className="rounded p-0.5 text-muted-foreground hover:text-foreground disabled:opacity-30"
                  title="Move down"
                >
                  <ArrowDown className="h-3 w-3" />
                </button>
              </span>
            )}
          </div>
        </td>
        <td className="px-3 py-2">
          <ResourceTypeBadge type={resource.resourceType} />
        </td>
        {showMatch && (
          <td className="px-3 py-2">
            <MatchBadge strategy={resource.matchStrategy} />
          </td>
        )}
        <td className="px-3 py-2">
          <ActionBadge action={resource.action} />
        </td>
        {showDiff && (
          <td className="px-3 py-2">
            {hasDiff && (
              <button
                onClick={onExpand}
                className="rounded p-0.5 text-muted-foreground hover:text-foreground"
              >
                {expanded ? (
                  <ChevronDown className="h-4 w-4" />
                ) : (
                  <ChevronRight className="h-4 w-4" />
                )}
              </button>
            )}
          </td>
        )}
      </tr>
      {expanded && hasDiff && (
        <tr>
          <td colSpan={showMatch ? 6 : 5} className="px-3 py-2">
            <ResourceDiffViewer
              sourceContent={resource.sourceContent}
              targetContent={resource.targetContent}
            />
          </td>
        </tr>
      )}
    </>
  );
}
