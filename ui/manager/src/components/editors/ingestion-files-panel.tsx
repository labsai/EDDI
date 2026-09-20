import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  AlertCircle,
  CheckCircle2,
  FileText,
  Loader2,
  Trash2,
  Upload,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { useDeleteSourceFile, useSourceFiles } from "@/hooks/use-ingestion-sources";
import { uploadSourceFile, type IngestedFile } from "@/lib/api/ingestion-sources";
import { getErrorMessage } from "@/lib/api-client";

/**
 * The files of an ingestion source of type `upload`.
 *
 * Dropping files here stores them; it does not embed them. Running the source
 * is what reads them — which is the same verb every other source answers to,
 * and is why an upload source can be re-run after a model change instead of
 * having to be filled in again by hand.
 */

/** What the file picker offers and what the drop zone accepts. */
const ACCEPTED_EXTENSIONS = [
  ".pdf",
  ".docx",
  ".xlsx",
  ".pptx",
  ".txt",
  ".md",
  ".markdown",
  ".csv",
  ".tsv",
  ".html",
  ".htm",
  ".json",
  ".xml",
  ".yaml",
  ".yml",
  ".log",
];

/**
 * How many files are uploaded at once.
 *
 * Three: enough that a folder of small documents finishes quickly, few enough
 * that a browser's six-connections-per-host limit still leaves room for the
 * rest of the Manager to answer while a large batch is in flight.
 */
const MAX_CONCURRENT_UPLOADS = 3;

type UploadState = "pending" | "uploading" | "done" | "failed";

interface UploadItem {
  /** Stable across re-renders; a file name is not unique within a drop. */
  key: string;
  fileName: string;
  sizeBytes: number;
  state: UploadState;
  progress: number;
  error?: string;
}

export interface IngestionFilesPanelProps {
  kbId?: string;
  sourceId?: string;
  version: number;
  readOnly?: boolean;
  testId: string;
}

export function IngestionFilesPanel({
  kbId,
  sourceId,
  version,
  readOnly,
  testId,
}: IngestionFilesPanelProps) {
  const { t } = useTranslation();
  const files = useSourceFiles(kbId, sourceId, version);
  const deleteMutation = useDeleteSourceFile(kbId, sourceId, version);

  const [uploads, setUploads] = useState<UploadItem[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<IngestedFile | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  // Counts enter/leave so that dragging over a child element does not flicker
  // the highlight off and on again.
  const dragDepth = useRef(0);
  const cancelled = useRef(false);

  useEffect(() => () => {
    cancelled.current = true;
  }, []);

  const patchUpload = useCallback((key: string, patch: Partial<UploadItem>) => {
    setUploads((current) =>
      current.map((item) => (item.key === key ? { ...item, ...patch } : item)),
    );
  }, []);

  const startUploads = useCallback(
    async (selected: File[]) => {
      if (!kbId || !sourceId || selected.length === 0) return;

      const queued: UploadItem[] = selected.map((file, index) => ({
        key: `${Date.now()}-${index}-${file.name}`,
        fileName: file.name,
        sizeBytes: file.size,
        state: "pending",
        progress: 0,
      }));
      setUploads((current) => [...current, ...queued]);

      let next = 0;
      const worker = async () => {
        while (next < selected.length) {
          const index = next++;
          const file = selected[index];
          const item = queued[index];
          // Both are indexed from the same array, so this never fires — it is
          // here because `noUncheckedIndexedAccess` cannot know that.
          if (!file || !item) continue;
          patchUpload(item.key, { state: "uploading" });
          try {
            const result = await uploadSourceFile(kbId, sourceId, version, file, {
              onProgress: (fraction) => patchUpload(item.key, { progress: fraction }),
            });
            const refusal = result.rejected[0];
            if (refusal) {
              // The server took the request and refused this file, with a
              // sentence saying why. That sentence is the whole value of the
              // response — replacing it with "upload failed" would leave the
              // operator guessing between a size limit and an unreadable file.
              patchUpload(item.key, { state: "failed", error: refusal.reason, progress: 1 });
            } else {
              patchUpload(item.key, { state: "done", progress: 1 });
            }
          } catch (error) {
            patchUpload(item.key, { state: "failed", error: getErrorMessage(error) });
          }
        }
      };

      await Promise.all(
        Array.from({ length: Math.min(MAX_CONCURRENT_UPLOADS, selected.length) }, worker),
      );
      // Refetched once at the end rather than after each file: the list is what
      // the source actually holds, and thirty refetches would show it thirty
      // times mid-flight.
      if (!cancelled.current) {
        void files.refetch();
      }
    },
    [kbId, sourceId, version, patchUpload, files],
  );

  const onDrop = (event: React.DragEvent) => {
    event.preventDefault();
    dragDepth.current = 0;
    setIsDragging(false);
    if (readOnly) return;
    const dropped = Array.from(event.dataTransfer.files ?? []);
    void startUploads(dropped);
  };

  const isBusy = uploads.some((item) => item.state === "uploading" || item.state === "pending");
  const finished = uploads.filter((item) => item.state === "done" || item.state === "failed");
  const canUpload = Boolean(kbId && sourceId) && !readOnly;

  return (
    <div className="space-y-3" data-testid={`${testId}-files`}>
      {!kbId || !sourceId ? (
        <p className="text-xs italic text-muted-foreground" data-testid={`${testId}-files-save-first`}>
          {t(
            "ragEditor.sources.filesSaveFirst",
            "Save the knowledge base once, and you can upload files to this source.",
          )}
        </p>
      ) : (
        <>
          <div
            onDragEnter={(event) => {
              event.preventDefault();
              dragDepth.current += 1;
              if (canUpload) setIsDragging(true);
            }}
            onDragOver={(event) => event.preventDefault()}
            onDragLeave={(event) => {
              event.preventDefault();
              dragDepth.current = Math.max(0, dragDepth.current - 1);
              if (dragDepth.current === 0) setIsDragging(false);
            }}
            onDrop={onDrop}
            className={cn(
              "rounded-lg border-2 border-dashed px-4 py-6 text-center transition-colors",
              isDragging
                ? "border-primary bg-primary/5"
                : "border-border bg-muted/20",
              !canUpload && "opacity-60",
            )}
            data-testid={`${testId}-dropzone`}
            data-dragging={isDragging ? "true" : "false"}
          >
            <Upload className="mx-auto mb-2 h-6 w-6 text-muted-foreground" aria-hidden="true" />
            <p className="text-sm text-foreground">
              {t("ragEditor.sources.dropFiles", "Drop files here, or")}{" "}
              <button
                type="button"
                className="font-medium text-primary underline underline-offset-2 disabled:no-underline"
                onClick={() => inputRef.current?.click()}
                disabled={!canUpload}
                data-testid={`${testId}-files-browse`}
              >
                {t("ragEditor.sources.browseFiles", "choose them")}
              </button>
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {t("ragEditor.sources.acceptedFormats", "PDF, Word, Excel, PowerPoint, text, Markdown, CSV and HTML")}
            </p>
            <input
              ref={inputRef}
              type="file"
              multiple
              accept={ACCEPTED_EXTENSIONS.join(",")}
              className="hidden"
              onChange={(event) => {
                const selected = Array.from(event.target.files ?? []);
                // Cleared so that choosing the same file twice in a row still
                // fires a change event.
                event.target.value = "";
                void startUploads(selected);
              }}
              data-testid={`${testId}-files-input`}
            />
          </div>

          {uploads.length > 0 && (
            <ul className="space-y-1.5" data-testid={`${testId}-upload-list`}>
              {uploads.map((item) => (
                <UploadRow key={item.key} item={item} testId={testId} />
              ))}
            </ul>
          )}

          {finished.length > 0 && !isBusy && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setUploads([])}
              data-testid={`${testId}-clear-uploads`}
            >
              <X />
              {t("ragEditor.sources.clearUploadList", "Clear list")}
            </Button>
          )}

          <FileList
            files={files.data ?? []}
            isLoading={files.isLoading}
            readOnly={readOnly}
            onDelete={setConfirmDelete}
            testId={testId}
          />

          {deleteMutation.isError && (
            <p className="text-xs text-destructive" role="alert" data-testid={`${testId}-file-delete-error`}>
              {getErrorMessage(deleteMutation.error)}
            </p>
          )}
        </>
      )}

      <AlertDialog
        open={confirmDelete !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmDelete(null);
        }}
        title={t("ragEditor.sources.deleteFileTitle", "Delete this file?")}
        description={t(
          "ragEditor.sources.deleteFileDescription",
          "{{fileName}} and everything the knowledge base learned from it are removed. Agents stop answering from it immediately.",
          { fileName: confirmDelete?.fileName ?? "" },
        )}
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        variant="destructive"
        isPending={deleteMutation.isPending}
        onConfirm={async () => {
          if (!confirmDelete) return;
          try {
            await deleteMutation.mutateAsync(confirmDelete.fileId);
            setConfirmDelete(null);
          } catch {
            // Reported by the status line above; the dialog stays open.
          }
        }}
      />
    </div>
  );
}

function UploadRow({ item, testId }: { item: UploadItem; testId: string }) {
  const { t } = useTranslation();
  return (
    <li
      className="rounded-md border border-border bg-card/50 px-3 py-2 text-xs"
      data-testid={`${testId}-upload-${item.state}`}
    >
      <div className="flex items-center gap-2">
        {item.state === "done" && <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-500" />}
        {item.state === "failed" && <AlertCircle className="h-3.5 w-3.5 shrink-0 text-destructive" />}
        {(item.state === "uploading" || item.state === "pending") && (
          <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />
        )}
        <span className="flex-1 truncate text-foreground">{item.fileName}</span>
        <span className="shrink-0 text-muted-foreground">{formatBytes(item.sizeBytes)}</span>
      </div>
      {item.state === "uploading" && (
        <div
          className="mt-1.5 h-1 overflow-hidden rounded-full bg-muted"
          role="progressbar"
          aria-valuenow={Math.round(item.progress * 100)}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={item.fileName}
        >
          <div
            className="h-full bg-primary transition-[width]"
            style={{ width: `${Math.round(item.progress * 100)}%` }}
          />
        </div>
      )}
      {item.state === "failed" && (
        <p className="mt-1 text-destructive" role="alert">
          {item.error ?? t("ragEditor.sources.uploadFailed", "This file could not be uploaded.")}
        </p>
      )}
    </li>
  );
}

function FileList({
  files,
  isLoading,
  readOnly,
  onDelete,
  testId,
}: {
  files: IngestedFile[];
  isLoading: boolean;
  readOnly?: boolean;
  onDelete: (file: IngestedFile) => void;
  testId: string;
}) {
  const { t } = useTranslation();

  if (isLoading) {
    return (
      <p className="text-xs text-muted-foreground" data-testid={`${testId}-files-loading`}>
        {t("common.loading", "Loading…")}
      </p>
    );
  }
  if (files.length === 0) {
    return (
      <p className="text-xs text-muted-foreground" data-testid={`${testId}-files-empty`}>
        {t("ragEditor.sources.noFiles", "No files yet. Run the source after uploading to index them.")}
      </p>
    );
  }

  const totalBytes = files.reduce((sum, file) => sum + file.sizeBytes, 0);

  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-muted-foreground" data-testid={`${testId}-files-summary`}>
        {/* A label rather than a sentence, so no language has to agree a noun
            with a number that is 1 as often as it is 40. */}
        {t("ragEditor.sources.filesSummary", "Files: {{count}} ({{size}})", {
          count: files.length,
          size: formatBytes(totalBytes),
        })}
      </p>
      <ul className="divide-y divide-border rounded-md border border-border" data-testid={`${testId}-file-list`}>
        {files.map((file) => (
          <li key={file.fileId} className="flex items-center gap-2 px-3 py-2 text-xs">
            <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="flex-1 truncate text-foreground" title={file.fileName}>
              {file.fileName}
            </span>
            <span className="shrink-0 text-muted-foreground">{formatBytes(file.sizeBytes)}</span>
            {!readOnly && (
              <Button
                variant="ghost"
                size="icon"
                onClick={() => onDelete(file)}
                aria-label={t("ragEditor.sources.deleteFile", "Delete {{fileName}}", {
                  fileName: file.fileName,
                })}
                data-testid={`${testId}-file-delete`}
              >
                <Trash2 />
              </Button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Bytes as a person reads them, with one decimal past a kilobyte. */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
