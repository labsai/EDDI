import { useRef, useState, type DragEvent } from "react";
import { useTranslation } from "react-i18next";
import { ImageUp, Loader2, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { BrandAssetSlot } from "@/lib/brand";

/** Same cap as `BrandAssetValidator.MAX_BYTES`. */
export const MAX_ASSET_BYTES = 512 * 1024;

export interface BrandAssetSlotProps {
  slot: BrandAssetSlot;
  /** The file name under `/branding/`. */
  publicPath: string;
  label: string;
  hint: string;
  accept: string;
  /** Whether the slot has an image of its own. */
  uploaded: boolean;
  /** Cache buster for the preview image. */
  revision: number;
  readOnly: boolean;
  busy: boolean;
  /** A translated error from the last attempt, if it failed. */
  error: string | null;
  /** A note under the slot, such as what shows while it is empty. */
  note?: string | null;
  onUpload: (file: File) => void;
  onRemove: () => void;
  /** A file over {@link MAX_ASSET_BYTES}, refused before it is sent. */
  onTooLarge: () => void;
}

/**
 * One image slot: what `/branding/<slot>` shows now, a drop zone (or file
 * picker) that uploads on drop, and Remove. The server decides the type from the
 * file's bytes; `accept` only narrows the picker.
 */
export function BrandAssetSlotCard({
  slot,
  publicPath,
  label,
  hint,
  accept,
  uploaded,
  revision,
  readOnly,
  busy,
  error,
  note,
  onUpload,
  onRemove,
  onTooLarge,
}: BrandAssetSlotProps) {
  const { t } = useTranslation();
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const src = revision > 0 ? `/branding/${publicPath}?r=${revision}` : `/branding/${publicPath}`;

  const take = (file: File | undefined) => {
    if (!file || readOnly) return;
    if (file.size > MAX_ASSET_BYTES) {
      onTooLarge();
      return;
    }
    onUpload(file);
  };

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragging(false);
    take(e.dataTransfer.files?.[0]);
  };

  return (
    <div className="space-y-2 rounded-xl border border-border bg-card p-4" data-testid={`brand-asset-${slot}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium text-foreground">{label}</p>
          <p className="text-xs text-muted-foreground">{hint}</p>
        </div>
        {uploaded ? (
          <Badge variant="success" data-testid={`brand-asset-${slot}-uploaded`}>
            {t("branding.assets.uploaded", "Uploaded")}
          </Badge>
        ) : (
          <Badge variant="secondary" data-testid={`brand-asset-${slot}-default`}>
            {t("branding.assets.default", "Default")}
          </Badge>
        )}
      </div>

      <div
        role="button"
        tabIndex={readOnly ? -1 : 0}
        aria-disabled={readOnly || busy}
        aria-label={t("branding.assets.dropLabel", "Upload {{slot}}", { slot: label })}
        onClick={() => !readOnly && !busy && input.current?.click()}
        onKeyDown={(e) => {
          if (readOnly || busy) return;
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            input.current?.click();
          }
        }}
        onDragOver={(e) => {
          if (readOnly) return;
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cn(
          "flex h-24 items-center justify-center gap-3 rounded-lg border-2 border-dashed border-border bg-muted/30 p-2 transition-colors",
          !readOnly && "cursor-pointer hover:border-primary/50",
          dragging && "border-primary bg-primary/5",
        )}
        data-testid={`brand-asset-${slot}-drop`}
      >
        <img
          src={src}
          alt=""
          className={cn(
            "max-h-16 max-w-[50%] rounded object-contain",
            // The bundled EDDI wordmark is white: invert it on the light drop
            // zone (as the light sidebar does), and show the dark-mode slot on
            // a dark tile so it is judged where it will be used.
            slot === "logo" && !uploaded && "invert dark:invert-0",
            slot === "logoDark" && "bg-neutral-900 p-1",
          )}
          data-testid={`brand-asset-${slot}-image`}
        />
        {!readOnly && (
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            {busy ? (
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            ) : (
              <ImageUp className="h-4 w-4" aria-hidden="true" />
            )}
            {uploaded
              ? t("branding.assets.replace", "Drop or click to replace")
              : t("branding.assets.upload", "Drop or click to upload")}
          </span>
        )}
      </div>
      <input
        ref={input}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(e) => {
          take(e.target.files?.[0]);
          e.target.value = "";
        }}
        data-testid={`brand-asset-${slot}-input`}
      />

      {error && (
        <p className="text-xs text-destructive" role="alert" data-testid={`brand-asset-${slot}-error`}>
          {error}
        </p>
      )}
      {note && !error && (
        <p className="text-xs text-warn-700 dark:text-warn-400" data-testid={`brand-asset-${slot}-note`}>
          {note}
        </p>
      )}

      {uploaded && !readOnly && (
        <Button
          variant="ghost"
          size="sm"
          onClick={onRemove}
          disabled={busy}
          data-testid={`brand-asset-${slot}-remove`}
        >
          <Trash2 />
          {t("branding.assets.remove", "Remove")}
        </Button>
      )}
    </div>
  );
}
