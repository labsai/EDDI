import { useTranslation } from "react-i18next";
import { MessageSquareText, Star, ThumbsUp, Users } from "lucide-react";
import { StatCard } from "@/components/workforce/analytics/stat-card";
import { cn } from "@/lib/utils";
import { LOW_SAMPLE_THRESHOLD, type RatingSummary } from "@/lib/api/ratings";
import { countDelta, percent, pointDelta } from "./feedback-utils";

interface OverviewProps {
  current: RatingSummary | undefined;
  /** The same filter over the previous period, or undefined for "all time". */
  previous: RatingSummary | undefined;
  /** Per-day buckets of the current period. */
  trend: RatingSummary[];
  /** Per-version buckets — only when one agent is selected. */
  versions: RatingSummary[] | null;
  onTagClick: (tag: string) => void;
}

/**
 * KPI tiles with the change against the previous period, then the trend,
 * the score distribution, the most picked reasons and the by-version table.
 * Every chart is plain markup in the house style — the Manager ships no chart
 * library — and says its numbers in text for screen readers.
 */
export function FeedbackOverview({ current, previous, trend, versions, onTagClick }: OverviewProps) {
  const { t } = useTranslation();
  const vsPrevious = (delta: string | null) =>
    delta === null ? undefined : t("feedback.vsPrevious", { delta, defaultValue: "{{delta}} vs previous period" });
  const satisfaction = current?.csat ?? current?.thumbsUpRate ?? null;
  const previousSatisfaction = previous?.csat ?? previous?.thumbsUpRate ?? null;

  return (
    <div className="space-y-4" data-testid="feedback-overview">
      <div className="cq-stat-grid">
        <StatCard
          label={t("feedback.kpiRatings", "Ratings")}
          value={current?.count ?? 0}
          subtitle={vsPrevious(countDelta(current?.count, previous?.count))}
          icon={Users}
        />
        <StatCard
          label={t("feedback.kpiAverage", "Average score")}
          value={percent(current?.avgNormalized)}
          subtitle={vsPrevious(pointDelta(current?.avgNormalized, previous?.avgNormalized))}
          icon={Star}
        />
        <StatCard
          label={current?.csat !== null && current?.csat !== undefined ? t("feedback.kpiCsat", "CSAT (4–5 stars)") : t("feedback.kpiThumbsUp", "Thumbs up")}
          value={percent(satisfaction)}
          subtitle={vsPrevious(pointDelta(satisfaction, previousSatisfaction))}
          icon={ThumbsUp}
        />
        <StatCard
          label={t("feedback.kpiComments", "Comments")}
          value={current?.commentCount ?? 0}
          subtitle={vsPrevious(countDelta(current?.commentCount, previous?.commentCount))}
          icon={MessageSquareText}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <TrendChart buckets={trend} />
        <Distribution summary={current} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <TopTags summary={current} onTagClick={onTagClick} />
        {versions && <VersionTable buckets={versions} />}
      </div>
    </div>
  );
}

function Panel({ title, children, testId }: { title: string; children: React.ReactNode; testId: string }) {
  return (
    <section className="rounded-xl border border-border bg-card p-5" data-testid={testId}>
      <h3 className="mb-4 text-sm font-semibold">{title}</h3>
      {children}
    </section>
  );
}

function NoData() {
  const { t } = useTranslation();
  return <p className="text-sm text-muted-foreground">{t("feedback.noData", "No ratings in this selection.")}</p>;
}

function TrendChart({ buckets }: { buckets: RatingSummary[] }) {
  const { t } = useTranslation();
  const maxCount = Math.max(1, ...buckets.map((b) => b.count));
  return (
    <Panel title={t("feedback.trend", "Trend")} testId="feedback-trend">
      {buckets.length === 0 ? (
        <NoData />
      ) : (
        <ul className="flex h-40 items-end gap-1" aria-label={t("feedback.trendLabel", "Average score and number of ratings per day")}>
          {buckets.map((b) => {
            const avg = b.avgNormalized ?? 0;
            return (
              <li
                key={b.bucket}
                className="group flex h-full flex-1 flex-col justify-end"
                title={`${b.bucket}: ${percent(b.avgNormalized)} · ${b.count}`}
                data-testid={`feedback-trend-${b.bucket}`}
              >
                <span className="sr-only">{`${b.bucket}: ${percent(b.avgNormalized)}, ${b.count}`}</span>
                <div className="rounded-t bg-primary/70" style={{ height: `${Math.max(4, avg * 100)}%` }} aria-hidden="true" />
                <div
                  className="mt-1 rounded bg-muted-foreground/30"
                  style={{ height: `${Math.max(2, (b.count / maxCount) * 20)}%` }}
                  aria-hidden="true"
                />
              </li>
            );
          })}
        </ul>
      )}
      <p className="mt-2 text-xs text-muted-foreground">{t("feedback.trendLegend", "Gold: average score · grey: number of ratings")}</p>
    </Panel>
  );
}

function Bar({ label, value, total, testId }: { label: string; value: number; total: number; testId: string }) {
  const share = total > 0 ? value / total : 0;
  return (
    <div className="flex items-center gap-3 text-sm" data-testid={testId}>
      <span className="w-20 shrink-0 text-muted-foreground">{label}</span>
      <div className="h-3 flex-1 overflow-hidden rounded-full bg-muted" aria-hidden="true">
        <div className="h-full rounded-full bg-primary" style={{ width: `${share * 100}%` }} />
      </div>
      <span className="w-10 shrink-0 text-end tabular-nums">{value}</span>
    </div>
  );
}

function Distribution({ summary }: { summary: RatingSummary | undefined }) {
  const { t } = useTranslation();
  const stars = summary?.distribution.stars ?? {};
  const starTotal = Object.values(stars).reduce((a, b) => a + b, 0);
  const thumbs = summary ? summary.distribution.thumbsUp + summary.distribution.thumbsDown : 0;
  return (
    <Panel title={t("feedback.distribution", "Distribution")} testId="feedback-distribution">
      {starTotal === 0 && thumbs === 0 ? (
        <NoData />
      ) : (
        <div className="space-y-2">
          {starTotal > 0 &&
            [5, 4, 3, 2, 1].map((n) => (
              <Bar
                key={n}
                label={t("feedback.starsOf", { n, defaultValue: "{{n}} of 5 stars" })}
                value={stars[String(n)] ?? 0}
                total={starTotal}
                testId={`feedback-dist-star-${n}`}
              />
            ))}
          {thumbs > 0 && (
            <>
              <Bar label={t("feedback.thumbsUp", "Thumbs up")} value={summary!.distribution.thumbsUp} total={thumbs} testId="feedback-dist-up" />
              <Bar label={t("feedback.thumbsDown", "Thumbs down")} value={summary!.distribution.thumbsDown} total={thumbs} testId="feedback-dist-down" />
            </>
          )}
        </div>
      )}
    </Panel>
  );
}

function TopTags({ summary, onTagClick }: { summary: RatingSummary | undefined; onTagClick: (tag: string) => void }) {
  const { t } = useTranslation();
  const tags = summary?.topTags ?? [];
  return (
    <Panel title={t("feedback.topTags", "Most picked reasons")} testId="feedback-top-tags">
      {tags.length === 0 ? (
        <NoData />
      ) : (
        <ul className="flex flex-wrap gap-2">
          {tags.map((tag) => (
            <li key={tag.tag}>
              <button
                type="button"
                className="rounded-full border border-border px-3 py-1 text-xs hover:border-primary hover:bg-primary/10"
                onClick={() => onTagClick(tag.tag)}
                data-testid={`feedback-tag-${tag.tag}`}
              >
                {tag.tag} <span className="text-muted-foreground tabular-nums">{tag.count}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function VersionTable({ buckets }: { buckets: RatingSummary[] }) {
  const { t } = useTranslation();
  return (
    <Panel title={t("feedback.byVersion", "By agent version")} testId="feedback-versions">
      {buckets.length === 0 ? (
        <NoData />
      ) : (
        <>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-muted-foreground">
                <th className="py-2 text-start font-medium">{t("feedback.version", "Version")}</th>
                <th className="py-2 text-end font-medium">{t("feedback.kpiRatings", "Ratings")}</th>
                <th className="py-2 text-end font-medium">{t("feedback.kpiAverage", "Average score")}</th>
              </tr>
            </thead>
            <tbody>
              {buckets.map((b) => {
                const low = b.count < LOW_SAMPLE_THRESHOLD;
                return (
                  <tr
                    key={b.bucket}
                    className={cn("border-b border-border/30", low && "text-muted-foreground/60")}
                    data-testid={`feedback-version-${b.bucket}`}
                    data-low-sample={low ? "true" : undefined}
                  >
                    <td className="py-2">v{b.bucket}</td>
                    <td className="py-2 text-end tabular-nums">{b.count}</td>
                    <td className="py-2 text-end tabular-nums">{percent(b.avgNormalized)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="mt-2 text-xs text-muted-foreground">
            {t("feedback.lowSample", { n: LOW_SAMPLE_THRESHOLD, defaultValue: "Greyed rows have fewer than {{n}} ratings — too few to compare." })}
          </p>
        </>
      )}
    </Panel>
  );
}
