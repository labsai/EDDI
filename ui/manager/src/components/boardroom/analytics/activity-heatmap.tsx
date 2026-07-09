import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface ActivityHeatmapProps {
  data: Array<{ date: string; count: number }>;
}

function getIntensityClass(count: number, max: number): string {
  if (count === 0) return "bg-muted";
  const ratio = max > 0 ? count / max : 0;
  if (ratio <= 0.25) return "bg-primary/20";
  if (ratio <= 0.5) return "bg-primary/40";
  if (ratio <= 0.75) return "bg-primary/60";
  return "bg-primary";
}

function ActivityHeatmap({ data }: ActivityHeatmapProps) {
  const { t } = useTranslation();
  const max = Math.max(...data.map((d) => d.count), 1);

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.activity", "Activity")}
      </h3>
      <div className="flex flex-wrap gap-1">
        {data.map((day) => (
          <div
            key={day.date}
            className={cn(
              "h-4 w-4 rounded-sm transition-colors",
              getIntensityClass(day.count, max),
            )}
            title={`${day.date}: ${day.count}`}
          />
        ))}
      </div>
    </div>
  );
}

export { ActivityHeatmap };
export type { ActivityHeatmapProps };
