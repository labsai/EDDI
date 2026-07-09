import { useTranslation } from "react-i18next";
import type { StyleCount } from "@/hooks/use-boardroom-analytics";
import { STYLE_INFO } from "@/lib/api/groups";

interface StyleBreakdownProps {
  data: StyleCount[];
}

function StyleBreakdown({ data }: StyleBreakdownProps) {
  const { t } = useTranslation();
  const max = Math.max(...data.map((d) => d.count), 1);

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.styleBreakdown", "Discussion Styles")}
      </h3>

      {data.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t("analyticsPage.noStyles", "No style data available.")}
        </p>
      ) : (
        <div className="space-y-3">
          {data.map((item) => {
            const info = STYLE_INFO[item.style];
            const pct = Math.round((item.count / max) * 100);
            return (
              <div key={item.style} className="flex items-center gap-3">
                <span className="w-6 text-center text-base" aria-hidden="true">
                  {info?.icon ?? "📋"}
                </span>
                <span className="w-28 shrink-0 truncate text-sm">
                  {info?.label ?? item.style}
                </span>
                <div className="h-3 flex-1 overflow-hidden rounded-full bg-muted">
                  <div
                    className="bg-primary/30 h-3 rounded-full transition-all duration-700"
                    style={{ width: `${pct}%` }}
                  />
                </div>
                <span className="w-8 text-end text-xs tabular-nums text-muted-foreground">
                  {item.count}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export { StyleBreakdown };
export type { StyleBreakdownProps };
