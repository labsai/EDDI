import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface ActivityHeatmapProps {
  data: Array<{ date: string; count: number }>;
  selectedDate?: string | null;
  onSelectDate?: (date: string | null) => void;
}

function getIntensityClass(count: number, max: number): string {
  if (count === 0) return "bg-muted";
  const ratio = max > 0 ? count / max : 0;
  if (ratio <= 0.25) return "bg-primary/20";
  if (ratio <= 0.5) return "bg-primary/40";
  if (ratio <= 0.75) return "bg-primary/60";
  return "bg-primary";
}

function ActivityHeatmap({ data, selectedDate, onSelectDate }: ActivityHeatmapProps) {
  const { t } = useTranslation();
  const max = Math.max(...data.map((d) => d.count), 1);

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <div className="mb-4 flex items-baseline justify-between">
        <h3 className="text-sm font-semibold">
          {t("analyticsPage.activity", "Activity")}
        </h3>
        <span className="text-xs text-muted-foreground">
          {t("analyticsPage.last30Days", "Last 30 days")}
        </span>
      </div>
      <div className="flex flex-wrap gap-1">
        {data.map((day) => (
          <button
            key={day.date}
            type="button"
            className={cn(
              "h-4 w-4 rounded-sm transition-colors",
              getIntensityClass(day.count, max),
              day.count > 0 && "cursor-pointer",
              selectedDate === day.date && "ring-2 ring-primary",
            )}
            title={`${day.date}: ${day.count}`}
            onClick={() => {
              if (day.count === 0) return;
              onSelectDate?.(selectedDate === day.date ? null : day.date);
            }}
          />
        ))}
      </div>
    </div>
  );
}

export { ActivityHeatmap };
export type { ActivityHeatmapProps };
