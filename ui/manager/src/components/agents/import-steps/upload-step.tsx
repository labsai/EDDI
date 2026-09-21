import { useRef } from "react";
import { useTranslation } from "react-i18next";
import { FileArchive } from "lucide-react";

interface UploadStepProps {
  dragging: boolean;
  onDragging: (v: boolean) => void;
  onFile: (file: File) => void;
}

export function UploadStep({ dragging, onDragging, onFile }: UploadStepProps) {
  const { t } = useTranslation();
  const fileInputRef = useRef<HTMLInputElement>(null);

  function handleFileDrop(e: React.DragEvent) {
    e.preventDefault();
    onDragging(false);
    const droppedFile = e.dataTransfer.files[0];
    if (droppedFile?.name.endsWith(".zip")) {
      onFile(droppedFile);
    }
  }

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    if (f?.name.endsWith(".zip")) {
      onFile(f);
    }
  }

  return (
    <div
      className={`flex-1 flex flex-col items-center justify-center gap-4 rounded-lg border-2 border-dashed p-8 transition-colors cursor-pointer ${
        dragging
          ? "border-primary bg-primary/5 scale-[1.01]"
          : "border-border hover:border-primary/50"
      }`}
      onDragOver={(e) => e.preventDefault()}
      onDragEnter={() => onDragging(true)}
      onDragLeave={() => onDragging(false)}
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
  );
}
