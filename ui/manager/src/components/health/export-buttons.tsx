import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Download } from "lucide-react";
import { downloadHealthExport, type HealthPeriod } from "@/lib/api/health";
import { Button, getErrorMessage } from "./deps";

/** JSON and CSV export of the daily rollup for the period on screen. */
export function ExportButtons({ agentId, period }: { agentId: string; period: HealthPeriod }) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState<"json" | "csv" | null>(null);
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
  return (
    <div className="inline-flex gap-1" role="group" aria-label={t("health.export.label", "Export")}>
      {(["json", "csv"] as const).map((format) => (
        <Button key={format} size="sm" variant="outline" disabled={busy !== null} onClick={() => void run(format)} data-testid={`health-export-${format}`}>
          <Download aria-hidden="true" />
          {format === "json" ? t("health.export.json", "Export JSON") : t("health.export.csv", "Export CSV")}
        </Button>
      ))}
    </div>
  );
}
