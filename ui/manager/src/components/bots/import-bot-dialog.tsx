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
} from "lucide-react";
import { useImportBot, usePreviewImport, useImportBotMerge } from "@/hooks/use-backup";
import type { ImportPreview } from "@/lib/api/backup";
import { Button } from "@/components/ui/button";

interface ImportBotDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

type Step = "upload" | "strategy" | "preview" | "importing";

export function ImportBotDialog({ open, onClose, onSuccess }: ImportBotDialogProps) {
  const { t } = useTranslation();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [step, setStep] = useState<Step>("upload");
  const [file, setFile] = useState<File | null>(null);
  const [strategy, setStrategy] = useState<"create" | "merge">("create");
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const importMutation = useImportBot();
  const previewMutation = usePreviewImport();
  const mergeMutation = useImportBotMerge();

  const reset = useCallback(() => {
    setStep("upload");
    setFile(null);
    setStrategy("create");
    setPreview(null);
    setSelected(new Set());
    setError(null);
  }, []);

  function handleClose() {
    reset();
    onClose();
  }

  function handleFileDrop(e: React.DragEvent) {
    e.preventDefault();
    const droppedFile = e.dataTransfer.files[0];
    if (droppedFile?.name.endsWith(".zip")) {
      setFile(droppedFile);
      setStep("strategy");
    }
  }

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0];
    if (selected) {
      setFile(selected);
      setStep("strategy");
    }
  }

  async function handleStrategyConfirm() {
    if (!file) return;
    setError(null);

    if (strategy === "create") {
      // Direct import — no preview needed
      setStep("importing");
      importMutation.mutate(file, {
        onSuccess: () => {
          onSuccess();
          handleClose();
        },
        onError: (err) => {
          setError(err.message);
          setStep("strategy");
        },
      });
    } else {
      // Merge — get preview first
      previewMutation.mutate(file, {
        onSuccess: (data) => {
          setPreview(data);
          // Select all updatable resources by default
          const allIds = new Set(data.resources.map((r) => r.originId));
          setSelected(allIds);
          setStep("preview");
        },
        onError: (err) => {
          setError(err.message);
        },
      });
    }
  }

  async function handleMergeConfirm() {
    if (!file) return;
    setError(null);
    setStep("importing");

    const selectedIds = Array.from(selected);
    mergeMutation.mutate(
      { file, selectedOriginIds: selectedIds },
      {
        onSuccess: () => {
          onSuccess();
          handleClose();
        },
        onError: (err) => {
          setError(err.message);
          setStep("preview");
        },
      }
    );
  }

  function toggleResource(originId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(originId)) {
        next.delete(originId);
      } else {
        next.add(originId);
      }
      return next;
    });
  }

  function toggleAll() {
    if (!preview) return;
    if (selected.size === preview.resources.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(preview.resources.map((r) => r.originId)));
    }
  }

  if (!open) return null;

  const isLoading = importMutation.isPending || previewMutation.isPending || mergeMutation.isPending;

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={!isLoading ? handleClose : undefined}
      />

      {/* Dialog */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-lg rounded-xl border bg-card p-6 shadow-2xl max-h-[80vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
          data-testid="import-bot-dialog"
        >
          {/* Header */}
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-lg font-semibold text-foreground">
              {t("importDialog.title", "Import Bot")}
            </h2>
            <button
              onClick={handleClose}
              disabled={isLoading}
              className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-50"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* Step: Upload */}
          {step === "upload" && (
            <div
              className="flex-1 flex flex-col items-center justify-center gap-4 rounded-lg border-2 border-dashed border-border p-8 hover:border-primary/50 transition-colors cursor-pointer"
              onDragOver={(e) => e.preventDefault()}
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
                  {t("importDialog.dropZoneHint", "Exported bot archive (.zip)")}
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

          {/* Step: Strategy */}
          {step === "strategy" && file && (
            <div className="flex-1 space-y-4">
              {/* File info */}
              <div className="flex items-center gap-3 rounded-lg bg-secondary/50 p-3">
                <FileArchive className="h-5 w-5 text-primary shrink-0" />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground truncate">{file.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {(file.size / 1024).toFixed(1)} KB
                  </p>
                </div>
              </div>

              {/* Strategy selection */}
              <fieldset className="space-y-2">
                <legend className="text-sm font-medium text-foreground mb-2">
                  {t("importDialog.strategyLabel", "Import Strategy")}
                </legend>

                <label
                  className={`flex items-start gap-3 rounded-lg border p-3 cursor-pointer transition-colors ${
                    strategy === "create"
                      ? "border-primary bg-primary/5"
                      : "border-border hover:border-primary/30"
                  }`}
                  data-testid="strategy-create"
                >
                  <input
                    type="radio"
                    name="strategy"
                    value="create"
                    checked={strategy === "create"}
                    onChange={() => setStrategy("create")}
                    className="mt-0.5 accent-primary"
                  />
                  <div>
                    <div className="flex items-center gap-1.5">
                      <Plus className="h-4 w-4 text-emerald-500" />
                      <span className="text-sm font-medium text-foreground">
                        {t("importDialog.createNew", "Create as new bot")}
                      </span>
                    </div>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {t("importDialog.createNewDesc", "Creates a fresh copy with new IDs. Best for first-time import.")}
                    </p>
                  </div>
                </label>

                <label
                  className={`flex items-start gap-3 rounded-lg border p-3 cursor-pointer transition-colors ${
                    strategy === "merge"
                      ? "border-primary bg-primary/5"
                      : "border-border hover:border-primary/30"
                  }`}
                  data-testid="strategy-merge"
                >
                  <input
                    type="radio"
                    name="strategy"
                    value="merge"
                    checked={strategy === "merge"}
                    onChange={() => setStrategy("merge")}
                    className="mt-0.5 accent-primary"
                  />
                  <div>
                    <div className="flex items-center gap-1.5">
                      <RefreshCw className="h-4 w-4 text-blue-500" />
                      <span className="text-sm font-medium text-foreground">
                        {t("importDialog.mergeSync", "Merge / sync with existing")}
                      </span>
                    </div>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {t("importDialog.mergeSyncDesc", "Updates existing resources if previously imported. Creates new ones if not found.")}
                    </p>
                  </div>
                </label>
              </fieldset>

              {error && (
                <p className="text-sm text-destructive">{error}</p>
              )}

              {/* Actions */}
              <div className="flex justify-between pt-2">
                <Button
                  variant="ghost"
                  onClick={() => { setFile(null); setStep("upload"); }}
                  disabled={isLoading}
                >
                  <ArrowLeft className="h-4 w-4" />
                  {t("common.back", "Back")}
                </Button>
                <Button
                  onClick={handleStrategyConfirm}
                  disabled={isLoading}
                  data-testid="import-confirm-strategy"
                >
                  {previewMutation.isPending
                    ? t("common.loading", "Loading...")
                    : strategy === "create"
                      ? t("importDialog.importNow", "Import Now")
                      : t("importDialog.previewChanges", "Preview Changes")}
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}

          {/* Step: Preview */}
          {step === "preview" && preview && (
            <div className="flex-1 flex flex-col space-y-4 min-h-0">
              {/* Bot name */}
              <div className="flex items-center gap-2">
                <FileArchive className="h-5 w-5 text-primary shrink-0" />
                <p className="text-sm font-semibold text-foreground">
                  {preview.botName || preview.botOriginId || "Bot"}
                </p>
              </div>

              {/* Resource table */}
              <div className="flex-1 overflow-auto rounded-lg border min-h-0">
                <table className="w-full text-sm">
                  <thead className="sticky top-0 bg-secondary/80 backdrop-blur-sm">
                    <tr>
                      <th className="px-3 py-2 text-start">
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
                      <th className="px-3 py-2 text-start text-xs font-medium text-muted-foreground uppercase">
                        {t("importDialog.action", "Action")}
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {preview.resources.map((r) => (
                      <tr
                        key={r.originId}
                        className={`transition-colors ${
                          selected.has(r.originId) ? "bg-primary/5" : ""
                        }`}
                      >
                        <td className="px-3 py-2">
                          <input
                            type="checkbox"
                            checked={selected.has(r.originId)}
                            onChange={() => toggleResource(r.originId)}
                            className="accent-primary"
                          />
                        </td>
                        <td className="px-3 py-2 font-medium text-foreground">
                          {r.name || r.originId.substring(0, 8)}
                        </td>
                        <td className="px-3 py-2">
                          <ResourceTypeBadge type={r.resourceType} />
                        </td>
                        <td className="px-3 py-2">
                          <ActionBadge action={r.action} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Summary */}
              <div className="flex gap-3 text-xs text-muted-foreground">
                <span>
                  {preview.resources.filter((r) => r.action === "CREATE").length} {t("importDialog.new", "new")}
                </span>
                <span>
                  {preview.resources.filter((r) => r.action === "UPDATE").length} {t("importDialog.updated", "updated")}
                </span>
                <span>
                  {selected.size} {t("importDialog.selected", "selected")}
                </span>
              </div>

              {error && (
                <p className="text-sm text-destructive">{error}</p>
              )}

              {/* Actions */}
              <div className="flex justify-between pt-2">
                <Button
                  variant="ghost"
                  onClick={() => setStep("strategy")}
                  disabled={isLoading}
                >
                  <ArrowLeft className="h-4 w-4" />
                  {t("common.back", "Back")}
                </Button>
                <Button
                  onClick={handleMergeConfirm}
                  disabled={isLoading || selected.size === 0}
                  data-testid="import-confirm-merge"
                >
                  {t("importDialog.mergeNow", "Import Selected")}
                  <Check className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}

          {/* Step: Importing */}
          {step === "importing" && (
            <div className="flex-1 flex flex-col items-center justify-center gap-4 py-8">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted-foreground border-t-primary" />
              <p className="text-sm text-muted-foreground">
                {t("importDialog.importing", "Importing bot...")}
              </p>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

function ResourceTypeBadge({ type }: { type: string }) {
  const colors: Record<string, string> = {
    bot: "bg-purple-500/10 text-purple-500",
    package: "bg-blue-500/10 text-blue-500",
    behavior: "bg-amber-500/10 text-amber-500",
    httpcalls: "bg-green-500/10 text-green-500",
    langchain: "bg-pink-500/10 text-pink-500",
    output: "bg-cyan-500/10 text-cyan-500",
    property: "bg-orange-500/10 text-orange-500",
    dictionary: "bg-teal-500/10 text-teal-500",
  };

  // Remove file extension suffix if present (e.g., "behavior" from "behavior.json")
  const cleanType = type.replace(/\.json$/, "");

  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${
        colors[cleanType] || "bg-secondary text-muted-foreground"
      }`}
    >
      {cleanType}
    </span>
  );
}

function ActionBadge({ action }: { action: string }) {
  switch (action) {
    case "CREATE":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-500">
          <Plus className="h-3 w-3" /> New
        </span>
      );
    case "UPDATE":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-blue-500">
          <RefreshCw className="h-3 w-3" /> Update
        </span>
      );
    case "SKIP":
      return (
        <span className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground">
          <Check className="h-3 w-3" /> Up to date
        </span>
      );
    default:
      return <span className="text-xs text-muted-foreground">{action}</span>;
  }
}
