import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Download } from "lucide-react";
import { downloadHealthExport, type HealthPeriod } from "./api";
import { Button, getErrorMessage } from "./deps";

/**
 * JSON and CSV export of the daily rollup. It carries the period only: the
 * version and origin filters on screen do not apply, and a note under the
 * buttons says so (visible, not only a tooltip).
 */
export function ExportButtons({ agentId, period, disabled }: { agentId: string; period: HealthPeriod; disabled?: boolean }) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState<"json" | "csv" | null>(null);
  /** Downloads the daily export in `format`. `busy` disables both buttons meanwhile; a failure is shown as a toast and re-enables them. */
  const run = async (format: "json" | "csv") => {
    setBusy(format);
    try {
      await downloadHealthExport(agentId, { format, period, kind: "daily" });
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setBusy(null);
    }
  };
  const noteId = useId();
  return (
    <div className="flex flex-col items-end gap-0.5">
      <div className="inline-flex gap-1" role="group" aria-label={t("health.export.label", "Export")} aria-describedby={noteId}>
        {(["json", "csv"] as const).map((format) => (
          <Button key={format} size="sm" variant="outline" disabled={busy !== null || disabled} onClick={() => void run(format)} data-testid={`health-export-${format}`}>
            <Download aria-hidden="true" />
            {format === "json" ? t("health.export.json", "Export JSON") : t("health.export.csv", "Export CSV")}
          </Button>
        ))}
      </div>
      <p id={noteId} className="text-xs text-muted-foreground" data-testid="health-export-note">
        {t("health.export.note", "Exports cover the selected period only, not the version or origin filters.")}
      </p>
    </div>
  );
}
