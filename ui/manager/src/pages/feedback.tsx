import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import { Bot, CheckCheck, Download, MessageSquareHeart, Star } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AgentPicker } from "@/components/shared/agent-picker";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { FeedbackFilterBar } from "@/components/feedback/feedback-filters";
import { FeedbackOverview } from "@/components/feedback/feedback-overview";
import { RatingsTable } from "@/components/feedback/ratings-table";
import {
  allRatingsQuery,
  attentionQuery,
  readFilters,
  writeFilter,
  type FeedbackFilters,
  type FeedbackTab,
} from "@/components/feedback/feedback-utils";
import { useIsAdminOrUnknown } from "@/hooks/use-auth";
import {
  isAgentRequired,
  isRatingChanged,
  useMarkRatingsReviewed,
  useRatingList,
  useRatingSummary,
  useReviewRating,
} from "@/hooks/use-ratings";
import { downloadRatingsCsv } from "@/lib/api/ratings";

const PAGE_SIZE = 25;

/**
 * Feedback — what people said about the agents, and the triage of it.
 *
 * Opens on "Needs attention": NEW ratings that are low or carry a comment,
 * newest first, each with Mark reviewed / note / open conversation, plus bulk
 * review. "All ratings" adds the full filter bar, KPI tiles against the
 * previous period, the trend, distribution, reasons and the by-version table.
 * Filters live in the URL.
 *
 * Scoping is the backend's: a non-administrator must pick an agent (the backend
 * answers 400 AGENT_ID_REQUIRED otherwise), which the page asks for up front
 * rather than showing an error.
 */
export function FeedbackPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const filters = readFilters(params);
  const mayListAll = useIsAdminOrUnknown();
  const needsAgent = !mayListAll && !filters.agentId;
  // A stable "now" per filter set, so period queries keep their cache key.
  const [now] = useState(() => Date.now());

  const setFilter = (key: keyof FeedbackFilters, value: string) => {
    const next = writeFilter(params, key, value);
    setParams(next, { replace: true });
    setPage(0);
    setSelected(new Set());
  };

  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const listQuery = filters.tab === "attention" ? attentionQuery(filters) : allRatingsQuery(filters, now);
  const list = useRatingList(listQuery, "newest", page * PAGE_SIZE, PAGE_SIZE, !needsAgent);
  const agentPrompt = needsAgent || isAgentRequired(list.error);

  // Triage can empty the current page; fall back to the last page that still exists.
  const listTotal = list.data?.total;
  useEffect(() => {
    if (listTotal === undefined || page === 0 || page * PAGE_SIZE < listTotal) return;
    setPage(Math.max(0, Math.ceil(listTotal / PAGE_SIZE) - 1));
  }, [listTotal, page]);

  const [exporting, setExporting] = useState(false);
  const exportCsv = async () => {
    setExporting(true);
    try {
      const { truncated } = await downloadRatingsCsv(listQuery);
      if (truncated) {
        toast.warning(t("feedback.exportTruncated", "The export stopped at 100,000 ratings — narrow the filter to get the rest."));
      }
    } catch {
      toast.error(t("feedback.exportFailed", "The export failed."));
    } finally {
      setExporting(false);
    }
  };

  const tabs: [FeedbackTab, string][] = [
    ["attention", t("feedback.tabAttention", "Needs attention")],
    ["all", t("feedback.tabAll", "All ratings")],
  ];

  return (
    <div className="space-y-6" data-testid="feedback-page">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <MessageSquareHeart className="h-8 w-8 text-primary" />
            {t("feedback.title", "Feedback")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("feedback.subtitle", "What people said about your agents — and what still needs a look.")}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" onClick={() => void exportCsv()} disabled={agentPrompt || exporting} data-testid="feedback-export-btn">
            <Download className="h-4 w-4" />
            {t("feedback.export", "Export CSV")}
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="w-full max-w-sm" data-testid="feedback-agent-picker">
          <span className="mb-1 block text-xs text-muted-foreground">
            {mayListAll ? t("feedback.agentOptional", "Agent (all when empty)") : t("feedback.agentRequired", "Agent")}
          </span>
          <AgentPicker
            value={filters.agentId}
            onChange={(id) => setFilter("agentId", id)}
            placeholder={t("feedback.pickAgent", "Pick an agent")}
          />
        </div>
      </div>

      <div role="tablist" aria-label={t("feedback.views", "Feedback views")} className="flex gap-1 border-b border-border">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`feedback-tab-${key}`}
            aria-selected={filters.tab === key}
            aria-controls={`feedback-panel-${key}`}
            tabIndex={filters.tab === key ? 0 : -1}
            onClick={() => setFilter("tab", key)}
            onKeyDown={(e) => {
              if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
                e.preventDefault();
                const next = key === "attention" ? "all" : "attention";
                setFilter("tab", next);
                document.getElementById(`feedback-tab-${next}`)?.focus();
              }
            }}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              filters.tab === key ? "border-primary text-foreground" : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
            data-testid={`feedback-tab-${key}`}
          >
            {label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`feedback-panel-${filters.tab}`} aria-labelledby={`feedback-tab-${filters.tab}`} className="space-y-6">
        {agentPrompt ? (
          <EmptyState
            icon={Bot}
            title={t("feedback.pickAgentTitle", "Pick an agent to see its feedback")}
            description={t("feedback.pickAgentDescription", "You see the ratings of the agents you can edit, one agent at a time.")}
          />
        ) : (
          <>
            {filters.tab === "all" && (
              <AllRatingsOverview filters={filters} now={now} onTagClick={(tag) => setFilter("tag", tag)} onFilter={setFilter} />
            )}
            {filters.tab === "attention" && (
              <BulkBar
                selected={selected}
                rows={list.data?.ratings ?? []}
                agentId={filters.agentId}
                total={list.data?.total ?? 0}
                onDone={() => setSelected(new Set())}
              />
            )}
            <RatingsList
              list={list}
              tab={filters.tab}
              selected={selected}
              onSelectedChange={setSelected}
              page={page}
              onPage={setPage}
            />
          </>
        )}
      </div>
    </div>
  );
}

function AllRatingsOverview({
  filters,
  now,
  onTagClick,
  onFilter,
}: {
  filters: FeedbackFilters;
  now: number;
  onTagClick: (tag: string) => void;
  onFilter: (key: keyof FeedbackFilters, value: string) => void;
}) {
  const current = useRatingSummary(allRatingsQuery(filters, now), "none");
  const hasPrevious = filters.period !== "all";
  const previous = useRatingSummary(allRatingsQuery(filters, now, 1), "none", hasPrevious);
  const trend = useRatingSummary(allRatingsQuery(filters, now), "day");
  const versions = useRatingSummary(allRatingsQuery(filters, now), "agentVersion", !!filters.agentId);

  return (
    <div className="space-y-4">
      <FeedbackFilterBar filters={filters} onChange={onFilter} />
      {current.isLoading ? (
        <div className="cq-stat-grid" data-testid="feedback-overview-loading">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="space-y-3 rounded-xl border border-border bg-card p-5">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-7 w-1/3" />
            </div>
          ))}
        </div>
      ) : (
        current.data && (
          <FeedbackOverview
            current={current.data.buckets[0]}
            previous={hasPrevious ? previous.data?.buckets[0] : undefined}
            trend={trend.data?.buckets ?? []}
            versions={filters.agentId ? (versions.data?.buckets ?? []) : null}
            onTagClick={onTagClick}
          />
        )
      )}
    </div>
  );
}

function BulkBar({
  selected,
  rows,
  agentId,
  total,
  onDone,
}: {
  selected: ReadonlySet<string>;
  rows: { id: string }[];
  agentId: string;
  total: number;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const review = useReviewRating();
  const markAll = useMarkRatingsReviewed();
  const [busy, setBusy] = useState(false);
  const chosen = useMemo(() => rows.filter((r) => selected.has(r.id)), [rows, selected]);

  const reviewSelected = async () => {
    setBusy(true);
    let changed = 0;
    for (const row of chosen) {
      try {
        await review.mutateAsync({ id: row.id, reviewStatus: "REVIEWED" });
      } catch (err) {
        if (isRatingChanged(err)) changed += 1;
        else toast.error(t("feedback.reviewFailed", "The review could not be saved."));
      }
    }
    setBusy(false);
    if (changed > 0) {
      toast.info(t("feedback.someChanged", { n: changed, defaultValue: "{{n}} of them were changed meanwhile and stay in the list." }));
    }
    onDone();
  };

  const reviewAll = async () => {
    try {
      const reviewed = await markAll.mutateAsync({ agentId: agentId || undefined, needsAttention: true });
      toast.success(t("feedback.markedAll", { n: reviewed, defaultValue: "Marked {{n}} reviewed." }));
      onDone();
    } catch {
      toast.error(t("feedback.reviewFailed", "The review could not be saved."));
    }
  };

  if (total === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="feedback-bulk-bar">
      <Button size="sm" variant="outline" disabled={chosen.length === 0 || busy} onClick={() => void reviewSelected()} data-testid="feedback-review-selected">
        <CheckCheck className="h-4 w-4" />
        {t("feedback.reviewSelected", { n: chosen.length, defaultValue: "Mark selected reviewed ({{n}})" })}
      </Button>
      <Button size="sm" variant="ghost" disabled={markAll.isPending || busy} onClick={() => void reviewAll()} data-testid="feedback-review-all">
        {t("feedback.reviewAll", "Mark everything here reviewed")}
      </Button>
    </div>
  );
}

function RatingsList({
  list,
  tab,
  selected,
  onSelectedChange,
  page,
  onPage,
}: {
  list: ReturnType<typeof useRatingList>;
  tab: FeedbackTab;
  selected: Set<string>;
  onSelectedChange: (next: Set<string>) => void;
  page: number;
  onPage: (page: number) => void;
}) {
  const { t } = useTranslation();
  const loadFailed = list.isError && !list.data;

  if (list.isLoading) {
    return (
      <div className="space-y-2" data-testid="feedback-loading">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-12 w-full" />
        ))}
      </div>
    );
  }
  if (loadFailed) {
    return <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => void list.refetch()} retryLabel={t("common.retry", "Retry")} />;
  }
  const rows = list.data?.ratings ?? [];
  if (rows.length === 0) {
    return tab === "attention" ? (
      <EmptyState
        icon={CheckCheck}
        title={t("feedback.allCaughtUp", "Nothing needs attention")}
        description={t("feedback.allCaughtUpDescription", "Low ratings and comments land here until someone marks them reviewed.")}
      />
    ) : (
      <EmptyState
        icon={Star}
        title={t("feedback.emptyTitle", "No ratings yet")}
        description={t(
          "feedback.emptyDescription",
          "Ratings appear once an agent asks for them: open the agent, turn on Ratings in its configuration (ratingConfig), save and deploy. People can then rate in the chat.",
        )}
      />
    );
  }
  const total = list.data?.total ?? rows.length;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  return (
    <div className="space-y-3">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground" data-testid="feedback-count">
        {t("feedback.countShown", { n: total, defaultValue: "Ratings: {{n}}" })}
      </p>
      <RatingsTable rows={rows} selectable={tab === "attention"} selected={selected} onSelectedChange={onSelectedChange} />
      {pages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => onPage(page - 1)} data-testid="feedback-prev">
            {t("feedback.previous", "Previous")}
          </Button>
          <span className="text-xs text-muted-foreground">
            {page + 1} / {pages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} data-testid="feedback-next">
            {t("feedback.next", "Next")}
          </Button>
        </div>
      )}
    </div>
  );
}
