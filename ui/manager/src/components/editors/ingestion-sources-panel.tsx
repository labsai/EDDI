import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  Clock,
  Eye,
  Globe,
  Loader2,
  Play,
  Plus,
  Trash2,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import {
  useIngestionRuns,
  usePreviewIngestionSource,
  usePurgeIngestionSource,
  useRunIngestionSource,
} from "@/hooks/use-ingestion-sources";
import type { IngestionReport, IngestionSource } from "@/lib/api/ingestion-sources";

export interface IngestionSourcesPanelProps {
  sources: IngestionSource[];
  onChange: (sources: IngestionSource[]) => void;
  /** Absent until the knowledge base has been saved once. */
  kbId?: string;
  version: number;
  readOnly?: boolean;
}

const DEFAULT_SOURCE: IngestionSource = {
  name: "",
  type: "web",
  enabled: true,
  web: {
    startUrl: "",
    sameSiteOnly: true,
    includeSubdomains: false,
    pathPrefix: "/",
    maxDepth: 3,
    maxPages: 200,
    excludePatterns: [],
    requestDelayMs: 500,
    respectRobots: true,
  },
};

/**
 * The knowledge base's ingestion sources.
 *
 * Editing a source is an edit of the RAG configuration — it saves with the rest
 * of the document. Only running, previewing, purging and reading history talk to
 * the backend directly, and only once the knowledge base has an id.
 */
export function IngestionSourcesPanel({
  sources,
  onChange,
  kbId,
  version,
  readOnly,
}: IngestionSourcesPanelProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState<string | null>(null);

  const update = (index: number, patch: Partial<IngestionSource>) => {
    onChange(sources.map((source, i) => (i === index ? { ...source, ...patch } : source)));
  };

  const updateWeb = (index: number, patch: Partial<NonNullable<IngestionSource["web"]>>) => {
    onChange(
      sources.map((source, i) =>
        i === index ? { ...source, web: { ...source.web, ...patch } } : source,
      ),
    );
  };

  const addSource = () => {
    onChange([...sources, { ...DEFAULT_SOURCE, web: { ...DEFAULT_SOURCE.web } }]);
    setExpanded(String(sources.length));
  };

  const removeSource = (index: number) => {
    onChange(sources.filter((_, i) => i !== index));
  };

  if (sources.length === 0) {
    return (
      <div data-testid="ingestion-sources-empty">
        <EmptyState
          icon={Globe}
          title={t("ragEditor.sources.emptyTitle", "No ingestion sources")}
          description={t(
            "ragEditor.sources.emptyDescription",
            "Add a source to have this knowledge base crawl a site and keep itself up to date.",
          )}
          actionLabel={readOnly ? undefined : t("ragEditor.sources.add", "Add source")}
          onAction={readOnly ? undefined : addSource}
        />
      </div>
    );
  }

  return (
    <div className="space-y-3" data-testid="ingestion-sources-panel">
      {sources.map((source, index) => {
        const key = source.id ?? String(index);
        const isOpen = expanded === key;
        return (
          <SourceCard
            key={key}
            source={source}
            isOpen={isOpen}
            onToggle={() => setExpanded(isOpen ? null : key)}
            onChange={(patch) => update(index, patch)}
            onChangeWeb={(patch) => updateWeb(index, patch)}
            onRemove={() => removeSource(index)}
            kbId={kbId}
            version={version}
            readOnly={readOnly}
            testId={`ingestion-source-${index}`}
          />
        );
      })}

      {!readOnly && (
        <Button variant="outline" size="sm" onClick={addSource} data-testid="add-ingestion-source">
          <Plus />
          {t("ragEditor.sources.add", "Add source")}
        </Button>
      )}
    </div>
  );
}

function SourceCard({
  source,
  isOpen,
  onToggle,
  onChange,
  onChangeWeb,
  onRemove,
  kbId,
  version,
  readOnly,
  testId,
}: {
  source: IngestionSource;
  isOpen: boolean;
  onToggle: () => void;
  onChange: (patch: Partial<IngestionSource>) => void;
  onChangeWeb: (patch: Partial<NonNullable<IngestionSource["web"]>>) => void;
  onRemove: () => void;
  kbId?: string;
  version: number;
  readOnly?: boolean;
  testId: string;
}) {
  const { t } = useTranslation();
  const [confirmPurge, setConfirmPurge] = useState(false);
  const [preview, setPreview] = useState<IngestionReport | null>(null);

  // Runtime actions need a saved source: the id is what the endpoints address.
  const isSaved = Boolean(kbId && source.id);

  const runs = useIngestionRuns(kbId, source.id, version, isSaved && isOpen);
  const runMutation = useRunIngestionSource(kbId, version);
  const previewMutation = usePreviewIngestionSource(kbId, version);
  const purgeMutation = usePurgeIngestionSource(kbId, version);

  const activeRun = runs.data?.find((run) => run.status === "RUNNING");
  const lastRun = runs.data?.[0];

  return (
    <div className="rounded-lg border border-border bg-card/50 overflow-hidden" data-testid={testId}>
      <div className="flex items-center gap-2.5 px-4 py-2.5">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={isOpen}
          className="flex flex-1 items-center gap-2.5 text-start"
          data-testid={`${testId}-toggle`}
        >
          <Globe className="h-4 w-4 shrink-0 text-sky-500" />
          <span className="flex-1 truncate text-sm font-medium text-foreground">
            {source.name || t("ragEditor.sources.unnamed", "Unnamed source")}
          </span>
          {source.enabled === false && (
            <Badge variant="secondary">{t("ragEditor.sources.disabled", "Disabled")}</Badge>
          )}
          {source.cron && (
            <Badge variant="outline" className="gap-1">
              <Clock className="h-3 w-3" />
              {source.cron}
            </Badge>
          )}
          {activeRun && (
            <Badge variant="warning" className="gap-1" data-testid={`${testId}-running`}>
              <Loader2 className="h-3 w-3 animate-spin" />
              {t("ragEditor.sources.running", "Running")}
            </Badge>
          )}
          <ChevronDown
            className={cn(
              "h-3.5 w-3.5 text-muted-foreground transition-transform",
              isOpen && "rotate-180",
            )}
          />
        </button>
        {!readOnly && (
          <Button
            variant="ghost"
            size="icon"
            onClick={onRemove}
            aria-label={t("ragEditor.sources.remove", "Remove source")}
            data-testid={`${testId}-remove`}
          >
            <X />
          </Button>
        )}
      </div>

      {isOpen && (
        <div className="space-y-4 border-t border-border px-4 py-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t("ragEditor.sources.name", "Name")}>
              <Input
                value={source.name ?? ""}
                onChange={(e) => onChange({ name: e.target.value })}
                disabled={readOnly}
                placeholder={t("ragEditor.sources.namePlaceholder", "public-docs")}
                data-testid={`${testId}-name`}
              />
            </Field>
            <Field label={t("ragEditor.sources.startUrl", "Start URL")}>
              <Input
                value={source.web?.startUrl ?? ""}
                onChange={(e) => onChangeWeb({ startUrl: e.target.value })}
                disabled={readOnly}
                placeholder="https://example.com/docs/"
                data-testid={`${testId}-start-url`}
              />
            </Field>
            <Field
              label={t("ragEditor.sources.pathPrefix", "Path prefix")}
              hint={t("ragEditor.sources.pathPrefixHint", "Only crawl under this path")}
            >
              <Input
                value={source.web?.pathPrefix ?? "/"}
                onChange={(e) => onChangeWeb({ pathPrefix: e.target.value })}
                disabled={readOnly}
                data-testid={`${testId}-path-prefix`}
              />
            </Field>
            <Field
              label={t("ragEditor.sources.cron", "Schedule (cron)")}
              hint={t("ragEditor.sources.cronHint", "Leave empty to run only by hand")}
            >
              <Input
                value={source.cron ?? ""}
                onChange={(e) => onChange({ cron: e.target.value || undefined })}
                disabled={readOnly}
                placeholder="0 2 * * *"
                data-testid={`${testId}-cron`}
              />
            </Field>
            <Field label={t("ragEditor.sources.maxDepth", "Max depth")}>
              <Input
                type="number"
                min={1}
                value={source.web?.maxDepth ?? 3}
                onChange={(e) => onChangeWeb({ maxDepth: Number(e.target.value) })}
                disabled={readOnly}
                data-testid={`${testId}-max-depth`}
              />
            </Field>
            <Field label={t("ragEditor.sources.maxPages", "Max pages")}>
              <Input
                type="number"
                min={1}
                value={source.web?.maxPages ?? 200}
                onChange={(e) => onChangeWeb({ maxPages: Number(e.target.value) })}
                disabled={readOnly}
                data-testid={`${testId}-max-pages`}
              />
            </Field>
          </div>

          <div className="flex flex-wrap gap-4">
            <Toggle
              label={t("ragEditor.sources.enabled", "Enabled")}
              checked={source.enabled !== false}
              onChange={(checked) => onChange({ enabled: checked })}
              disabled={readOnly}
              testId={`${testId}-enabled`}
            />
            <Toggle
              label={t("ragEditor.sources.respectRobots", "Respect robots.txt")}
              hint={t(
                "ragEditor.sources.respectRobotsHint",
                "Turn off only for a site you own",
              )}
              checked={source.web?.respectRobots !== false}
              onChange={(checked) => onChangeWeb({ respectRobots: checked })}
              disabled={readOnly}
              testId={`${testId}-respect-robots`}
            />
            <Toggle
              label={t("ragEditor.sources.includeSubdomains", "Include subdomains")}
              checked={source.web?.includeSubdomains === true}
              onChange={(checked) => onChangeWeb({ includeSubdomains: checked })}
              disabled={readOnly}
              testId={`${testId}-include-subdomains`}
            />
          </div>

          <Field
            label={t("ragEditor.sources.excludePatterns", "Exclude patterns")}
            hint={t(
              "ragEditor.sources.excludePatternsHint",
              "Globs matched against the URL path, comma separated — * stays in one segment, ** crosses them",
            )}
          >
            <Input
              value={(source.web?.excludePatterns ?? []).join(", ")}
              onChange={(e) =>
                onChangeWeb({
                  excludePatterns: e.target.value
                    .split(",")
                    .map((pattern) => pattern.trim())
                    .filter(Boolean),
                })
              }
              disabled={readOnly}
              placeholder="*.pdf, **/changelog/**"
              data-testid={`${testId}-exclude-patterns`}
            />
          </Field>

          {!isSaved ? (
            <p className="text-xs italic text-muted-foreground" data-testid={`${testId}-save-first`}>
              {t(
                "ragEditor.sources.saveFirst",
                "Save the knowledge base to run this source, preview it or see its history.",
              )}
            </p>
          ) : (
            <>
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  onClick={() => runMutation.mutate(source.id as string)}
                  disabled={readOnly || runMutation.isPending || Boolean(activeRun)}
                  data-testid={`${testId}-run`}
                >
                  {runMutation.isPending ? <Loader2 className="animate-spin" /> : <Play />}
                  {t("ragEditor.sources.run", "Run now")}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    previewMutation.mutate(source.id as string, { onSuccess: setPreview })
                  }
                  disabled={readOnly || previewMutation.isPending}
                  data-testid={`${testId}-preview`}
                >
                  {previewMutation.isPending ? <Loader2 className="animate-spin" /> : <Eye />}
                  {t("ragEditor.sources.preview", "Preview")}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setConfirmPurge(true)}
                  disabled={readOnly || purgeMutation.isPending}
                  data-testid={`${testId}-purge`}
                >
                  <Trash2 />
                  {t("ragEditor.sources.purge", "Purge state")}
                </Button>
              </div>

              {runMutation.isError && (
                <StatusLine
                  tone="error"
                  text={t(
                    "ragEditor.sources.runFailed",
                    "Could not start the run. A run may already be in flight.",
                  )}
                  testId={`${testId}-run-error`}
                />
              )}

              {preview && (
                <PreviewResult report={preview} onDismiss={() => setPreview(null)} testId={testId} />
              )}

              <RunHistory
                runs={runs.data ?? []}
                isLoading={runs.isLoading}
                lastRun={lastRun}
                testId={testId}
              />
            </>
          )}
        </div>
      )}

      <AlertDialog
        open={confirmPurge}
        onOpenChange={setConfirmPurge}
        title={t("ragEditor.sources.purgeTitle", "Purge ingestion state?")}
        description={t(
          "ragEditor.sources.purgeDescription",
          "The next run will re-crawl and re-embed everything this source has ingested. Documents already stored are not removed by this.",
        )}
        confirmLabel={t("ragEditor.sources.purgeConfirm", "Purge")}
        variant="warning"
        isPending={purgeMutation.isPending}
        onConfirm={() => {
          purgeMutation.mutate(source.id as string);
          setConfirmPurge(false);
        }}
      />
    </div>
  );
}

function PreviewResult({
  report,
  onDismiss,
  testId,
}: {
  report: IngestionReport;
  onDismiss: () => void;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="rounded-lg border border-border bg-muted/30 p-3 text-xs"
      data-testid={`${testId}-preview-result`}
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="font-semibold text-foreground">
          {t("ragEditor.sources.previewResult", "Preview — nothing was embedded")}
        </span>
        <Button variant="ghost" size="icon" onClick={onDismiss} aria-label={t("common.close", "Close")}>
          <X />
        </Button>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-muted-foreground sm:grid-cols-4">
        <Stat label={t("ragEditor.sources.statSeen", "Seen")} value={report.documentsSeen} />
        <Stat
          label={t("ragEditor.sources.statChanged", "Would ingest")}
          value={report.documentsIngested}
        />
        <Stat
          label={t("ragEditor.sources.statUnchanged", "Unchanged")}
          value={report.documentsUnchanged}
        />
        <Stat label={t("ragEditor.sources.statFailed", "Failed")} value={report.documentsFailed} />
      </dl>
      {report.tombstoningSkipped && (
        <StatusLine
          tone="warning"
          text={t(
            "ragEditor.sources.partialCrawl",
            "The crawl stopped at a limit, so nothing would be treated as deleted.",
          )}
          testId={`${testId}-partial`}
        />
      )}
    </div>
  );
}

function RunHistory({
  runs,
  isLoading,
  lastRun,
  testId,
}: {
  runs: { runId: string; status: string; startedAt?: string; documentsIngested: number; documentsUnchanged: number; documentsTombstoned: number; error?: string | null }[];
  isLoading: boolean;
  lastRun?: { status: string; error?: string | null };
  testId: string;
}) {
  const { t } = useTranslation();

  if (isLoading) {
    return (
      <p className="text-xs text-muted-foreground">{t("common.loading", "Loading…")}</p>
    );
  }
  if (runs.length === 0) {
    return (
      <p className="text-xs italic text-muted-foreground" data-testid={`${testId}-no-runs`}>
        {t("ragEditor.sources.noRuns", "This source has not run yet.")}
      </p>
    );
  }

  return (
    <div className="space-y-1" data-testid={`${testId}-runs`}>
      <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {t("ragEditor.sources.history", "Recent runs")}
      </span>
      {runs.slice(0, 5).map((run) => (
        <div
          key={run.runId}
          className="flex items-center gap-2 rounded-md border border-border/60 px-2 py-1.5 text-xs"
          data-testid={`${testId}-run-${run.runId}`}
        >
          {run.status === "COMPLETED" ? (
            <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" />
          ) : run.status === "RUNNING" ? (
            <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />
          ) : (
            <AlertCircle className="h-3.5 w-3.5 shrink-0 text-destructive" />
          )}
          <span className="text-muted-foreground">
            {run.startedAt ? new Date(run.startedAt).toLocaleString() : "—"}
          </span>
          <span className="ms-auto text-muted-foreground">
            {t("ragEditor.sources.runSummary", "{{ingested}} ingested · {{unchanged}} unchanged", {
              ingested: run.documentsIngested,
              unchanged: run.documentsUnchanged,
            })}
          </span>
        </div>
      ))}
      {lastRun?.error && (
        <StatusLine tone="error" text={lastRun.error} testId={`${testId}-last-error`} />
      )}
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block space-y-1">
      <span className="text-xs font-medium text-foreground/80">{label}</span>
      {children}
      {hint && <span className="block text-[11px] text-muted-foreground">{hint}</span>}
    </label>
  );
}

function Toggle({
  label,
  hint,
  checked,
  onChange,
  disabled,
  testId,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  testId: string;
}) {
  return (
    <label className="flex items-start gap-2 text-xs">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        disabled={disabled}
        className="mt-0.5 h-4 w-4 rounded border-input accent-primary"
        data-testid={testId}
      />
      <span>
        <span className="font-medium text-foreground/80">{label}</span>
        {hint && <span className="block text-[11px] text-muted-foreground">{hint}</span>}
      </span>
    </label>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-[11px] uppercase tracking-wide">{label}</dt>
      <dd className="font-semibold text-foreground">{value}</dd>
    </div>
  );
}

function StatusLine({
  tone,
  text,
  testId,
}: {
  tone: "error" | "warning";
  text: string;
  testId: string;
}) {
  return (
    <p
      className={cn(
        "mt-2 flex items-start gap-1.5 text-[11px]",
        tone === "error" ? "text-destructive" : "text-amber-700 dark:text-amber-400",
      )}
      data-testid={testId}
    >
      <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
      {text}
    </p>
  );
}
