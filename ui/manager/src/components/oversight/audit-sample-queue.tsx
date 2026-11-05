import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Check, Flag, ListChecks } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { oversightErrorCode, useAuditSample, useAuditSamples, useReviewSample } from "@/hooks/use-hitl-oversight";
import { OVERSIGHT_LIMITS, type AuditSample } from "@/lib/api/hitl-oversight";

const PAGE_SIZE = 20;

/**
 * The sampled-audit review queue: one sample at a time, by due date. OK or Flag
 * (a flag needs a reason code from the agent's vocabulary); an optional note.
 * There is deliberately no "select all" — a bulk OK is exactly the
 * rubber-stamping the queue exists to prevent.
 */
export function AuditSampleQueue({ agentId }: { agentId?: string }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const list = useAuditSamples(agentId, "PENDING", page * PAGE_SIZE, PAGE_SIZE);
  // Reviewing the last row of the last page shrinks the total: step back instead of
  // showing "no samples" while earlier pages still hold some.
  const knownTotal = list.data?.total;
  useEffect(() => {
    if (knownTotal === undefined) return;
    const lastPage = Math.max(0, Math.ceil(knownTotal / PAGE_SIZE) - 1);
    if (page > lastPage) setPage(lastPage);
  }, [knownTotal, page]);

  if (list.isLoading) {
    return (
      <div className="space-y-2" data-testid="oversight-samples-loading">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </div>
    );
  }
  if (list.isError && !list.data) {
    return <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => void list.refetch()} retryLabel={t("common.retry", "Retry")} />;
  }
  const rows = list.data?.rows ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        icon={ListChecks}
        title={t("oversight.samplesEmptyTitle", "No samples waiting for review")}
        description={t(
          "oversight.samplesEmptyDescription",
          "Tool calls that ran without approval are sampled here when the agent's hitlConfig.oversight.sampledAudit names them.",
        )}
      />
    );
  }
  const total = list.data?.total ?? rows.length;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  return (
    <div className="space-y-3" data-testid="oversight-sample-queue">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground" data-testid="oversight-samples-count">
        {t("oversight.samplesCount", { n: total, defaultValue: "Waiting for review: {{n}}" })}
      </p>
      {rows.map((sample) => (
        <SampleRow key={sample.id} sample={sample} />
      ))}
      {pages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)} data-testid="oversight-samples-prev">
            {t("oversight.previous", "Previous")}
          </Button>
          <span className="text-xs text-muted-foreground">
            {page + 1} / {pages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} data-testid="oversight-samples-next">
            {t("oversight.next", "Next")}
          </Button>
        </div>
      )}
    </div>
  );
}

function SampleRow({ sample }: { sample: AuditSample }) {
  const { t } = useTranslation();
  const [flagging, setFlagging] = useState(false);
  const [reasonCode, setReasonCode] = useState("");
  const [note, setNote] = useState("");
  const detail = useAuditSample(flagging ? sample.id : null);
  const review = useReviewSample();

  const submit = async (verdict: "OK" | "FLAGGED") => {
    try {
      await review.mutateAsync({
        id: sample.id,
        verdict: { verdict, reasonCode: verdict === "FLAGGED" ? reasonCode : undefined, note: note.trim() || undefined, expectedVersion: sample.version },
      });
      toast.success(verdict === "OK" ? t("oversight.reviewedOk", "Marked OK.") : t("oversight.reviewedFlagged", "Flagged."));
    } catch (err) {
      if (oversightErrorCode(err) === "SAMPLE_ALREADY_REVIEWED") {
        toast.info(t("oversight.alreadyReviewed", "Someone reviewed this sample meanwhile."));
      } else {
        toast.error(t("oversight.reviewFailed", "The verdict could not be saved."));
      }
    }
  };

  return (
    <div className="space-y-2 rounded-xl border border-border bg-card p-4" data-testid={`oversight-sample-${sample.id}`}>
      <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
        <span className="font-medium text-foreground">
          {sample.toolName}
          <span className="ms-2 font-mono text-xs text-muted-foreground">{sample.category}</span>
        </span>
        <span className="text-xs text-muted-foreground">
          {t("oversight.dueAt", { when: new Date(sample.reviewDueAt).toLocaleString(), defaultValue: "Review by {{when}}" })}
        </span>
      </div>
      <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all rounded bg-muted p-2 font-mono text-xs" data-testid="oversight-sample-args">
        {sample.argumentsRedacted}
      </pre>
      {sample.resultRedacted && (
        <pre className="max-h-32 overflow-auto whitespace-pre-wrap break-all rounded bg-muted/50 p-2 font-mono text-xs">{sample.resultRedacted}</pre>
      )}
      <textarea
        className="w-full rounded-md border border-input bg-background p-2 text-sm"
        rows={2}
        maxLength={OVERSIGHT_LIMITS.maxNoteLength}
        placeholder={t("oversight.notePlaceholder", "Note (optional)")}
        aria-label={t("oversight.noteLabel", "Note")}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        data-testid="oversight-sample-note"
      />
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="outline" disabled={review.isPending} onClick={() => void submit("OK")} data-testid="oversight-sample-ok">
          <Check className="h-4 w-4" />
          {t("oversight.ok", "OK")}
        </Button>
        {!flagging ? (
          <Button size="sm" variant="outline" onClick={() => setFlagging(true)} data-testid="oversight-sample-flag">
            <Flag className="h-4 w-4" />
            {t("oversight.flag", "Flag")}
          </Button>
        ) : (
          <>
            <select
              className="rounded-md border border-input bg-background px-2 py-1 text-sm"
              aria-label={t("oversight.reasonLabel", "Reason")}
              value={reasonCode}
              onChange={(e) => setReasonCode(e.target.value)}
              data-testid="oversight-sample-reason"
            >
              <option value="">{t("oversight.pickReason", "Pick a reason…")}</option>
              {(detail.data?.reasonCodes ?? []).map((code) => (
                <option key={code.code} value={code.code}>
                  {code.label}
                </option>
              ))}
            </select>
            <Button
              size="sm"
              variant="destructive"
              disabled={!reasonCode || review.isPending}
              onClick={() => void submit("FLAGGED")}
              data-testid="oversight-sample-flag-submit"
            >
              {t("oversight.flagSubmit", "Flag sample")}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}
