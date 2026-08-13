/**
 * A single pending-attachment chip: thumbnail/icon, name, size, status +
 * remove. Shared between the chat panel and the operator chat — moved out of
 * chat-panel when the operator input gained attachments.
 */
import { useTranslation } from "react-i18next";
import { AlertTriangle, FileText, Loader2, Paperclip, X } from "lucide-react";
import { formatBytes, isImageMime } from "@/lib/api/attachments";
import type { PendingAttachment } from "@/hooks/use-attachment-staging";
import { cn } from "@/lib/utils";

/**
 * Full-container overlay shown while a file drag hovers a chat drop zone.
 * The container must be `position: relative`; pointer events pass through so
 * the drop lands on the container's own handlers.
 */
export function FileDropOverlay() {
  const { t } = useTranslation();
  return (
    <div
      className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center rounded-xl border-2 border-dashed border-primary bg-background/80"
      data-testid="file-drop-overlay"
    >
      <div className="flex items-center gap-2 text-sm font-medium text-primary">
        <Paperclip className="h-5 w-5" />
        {t("chat.dropToAttach", "Drop files to attach")}
      </div>
    </div>
  );
}

export function PendingAttachmentChip({
  att,
  onRemove,
}: {
  att: PendingAttachment;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const isImage = isImageMime(att.file.type) && att.previewUrl;
  const isError = att.status === "error";

  return (
    <div
      className={cn(
        "group relative flex items-center gap-2 rounded-lg border bg-card px-2 py-1.5 pe-7 text-xs",
        isError ? "border-destructive/40" : "border-border"
      )}
      title={att.error ?? att.file.name}
      data-testid="attachment-chip"
    >
      {/* Thumbnail / icon */}
      {isImage ? (
        <img
          src={att.previewUrl}
          alt=""
          className="h-8 w-8 shrink-0 rounded object-cover"
          onError={(e) => {
            (e.target as HTMLElement).style.display = "none";
          }}
        />
      ) : (
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded bg-muted">
          {isError ? (
            <AlertTriangle className="h-4 w-4 text-destructive" />
          ) : (
            <FileText className="h-4 w-4 text-muted-foreground" />
          )}
        </div>
      )}

      {/* Name + size / status */}
      <div className="flex min-w-0 flex-col">
        <span className="max-w-[140px] truncate font-medium text-foreground">
          {att.file.name}
        </span>
        <span className={cn("truncate", isError ? "text-destructive" : "text-muted-foreground")}>
          {att.status === "uploading"
            ? t("chat.attachUploading", "Uploading...")
            : isError
              ? (att.error ?? t("chat.attachError", "Failed to upload file"))
              : formatBytes(att.result?.sizeBytes ?? att.file.size)}
        </span>
      </div>

      {/* Uploading spinner overlays the remove slot */}
      {att.status === "uploading" ? (
        <Loader2 className="absolute inset-e-1.5 top-1.5 h-4 w-4 animate-spin text-muted-foreground" />
      ) : (
        <button
          type="button"
          onClick={onRemove}
          className="absolute inset-e-1 top-1 flex h-5 w-5 items-center justify-center rounded-full text-muted-foreground/60 hover:bg-muted hover:text-foreground"
          title={t("common.remove", "Remove")}
          data-testid="attachment-remove"
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}
