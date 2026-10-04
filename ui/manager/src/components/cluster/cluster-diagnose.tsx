import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { AlertTriangle, CheckCircle2, CircleAlert, Info, Stethoscope } from "lucide-react";
import type { Finding } from "@/lib/api/cluster";
import { diagnosisVerdictLabel, findingActionLabel, findingText, formatDuration } from "@/lib/cluster-labels";
import { useDiagnosis, useReleaseLease } from "@/hooks/use-cluster";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/shared/error-state";
import { cn } from "@/lib/utils";

function FindingIcon({ severity }: { severity: Finding["severity"] }) {
  if (severity === "error") return <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />;
  if (severity === "warning") return <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />;
  return <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />;
}

/**
 * "Why is conversation X stuck?" — its lease and holder, where its turns are
 * queued, its state and dead letters, each finding with the next step.
 */
export function ClusterDiagnosePanel({
  initialId,
  canAct,
  onShowDeadLetters,
  onIdChange,
}: {
  initialId: string;
  canAct: boolean;
  onShowDeadLetters: (conversationId: string) => void;
  onIdChange: (id: string) => void;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(initialId);
  const [id, setId] = useState(initialId);
  const { data, isLoading, isError, refetch } = useDiagnosis(id);
  const release = useReleaseLease();
  const [confirmRelease, setConfirmRelease] = useState(false);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const next = draft.trim();
    setId(next);
    onIdChange(next);
  };

  const doRelease = () => {
    if (!data?.lease) return;
    release.mutate(
      { conversationId: id, expectedRevision: data.lease.revision },
      {
        onSuccess: (r) => {
          setConfirmRelease(false);
          if (r.outcome === "RENEWED") toast.warning(r.message);
          else toast.success(r.message);
        },
        onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
      },
    );
  };

  const verdictTone =
    data?.verdict === "STUCK"
      ? "border-destructive/40 bg-destructive/5"
      : data?.verdict === "NEEDS_ATTENTION"
        ? "border-warning/40 bg-warning/5"
        : "border-border bg-card";

  return (
    <section className="space-y-4" aria-labelledby="cluster-diagnose-heading" data-testid="cluster-diagnose">
      <h2 id="cluster-diagnose-heading" className="flex items-center gap-2 text-lg font-semibold text-foreground">
        <Stethoscope className="h-5 w-5 text-primary" aria-hidden="true" />
        {t("cluster.diagnose.title", "Why is this conversation stuck?")}
      </h2>
      <form className="flex max-w-xl gap-2" onSubmit={submit} role="search">
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={t("cluster.dl.filterConversation", "Conversation id")}
          aria-label={t("cluster.dl.filterConversation", "Conversation id")}
          data-testid="cluster-diagnose-input"
        />
        <Button type="submit" disabled={draft.trim().length === 0} data-testid="cluster-diagnose-submit">
          {t("cluster.diagnose.run", "Diagnose")}
        </Button>
      </form>

      {!id ? (
        <p className="text-sm text-muted-foreground">
          {t("cluster.diagnose.hint", "Paste the id of a conversation that does not answer or keeps returning 409.")}
        </p>
      ) : isLoading ? (
        <Skeleton className="h-32 w-full" />
      ) : isError && !data ? (
        <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => refetch()} retryLabel={t("common.retry", "Retry")} />
      ) : data ? (
        <div className={cn("space-y-4 rounded-xl border p-5", verdictTone)} data-testid="cluster-diagnosis" data-verdict={data.verdict}>
          <div className="flex flex-wrap items-center gap-2">
            {data.verdict === "OK" ? (
              <CheckCircle2 className="h-6 w-6 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
            ) : (
              <AlertTriangle className="h-6 w-6 text-warning" aria-hidden="true" />
            )}
            <h3 className="text-lg font-semibold text-foreground" data-testid="cluster-diagnosis-verdict">
              {diagnosisVerdictLabel(t, data.verdict)}
            </h3>
            {data.exists && (
              <Link to={`/manage/conversationview/${data.conversationId}`} className="text-sm text-primary hover:underline">
                {t("cluster.diagnose.openConversation", "Open conversation")}
              </Link>
            )}
          </div>
          {data.exists && (
            <dl className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
              <div>
                <dt className="text-xs text-muted-foreground">{t("cluster.dl.colAgent", "Agent")}</dt>
                <dd className="font-mono text-xs">
                  {data.agentId ?? "—"}
                  {data.agentVersion != null ? ` v${data.agentVersion}` : ""}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">{t("cluster.diagnose.state", "State")}</dt>
                <dd>
                  <Badge variant="outline">{data.state ?? "—"}</Badge>
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">{t("cluster.diagnose.steps", "Steps")}</dt>
                <dd className="tabular-nums">{data.steps}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">{t("cluster.diagnose.lease", "Lease")}</dt>
                <dd className="text-xs" data-testid="cluster-diagnosis-lease">
                  {data.lease
                    ? t("cluster.diagnose.leaseValue", "{{node}}, held {{age}}, token {{rev}}", {
                        node: data.lease.holderNode,
                        age: formatDuration(data.lease.ageMs),
                        rev: data.lease.revision,
                      })
                    : t("cluster.diagnose.noLease", "none")}
                </dd>
              </div>
            </dl>
          )}
          <ul className="space-y-3" data-testid="cluster-diagnosis-findings">
            {data.findings.map((f, i) => {
              const action = findingActionLabel(t, f.action);
              return (
                <li key={`${f.code}-${i}`} className="flex gap-2 text-sm" data-testid={`cluster-finding-${f.code}`}>
                  <FindingIcon severity={f.severity} />
                  <div className="space-y-1">
                    <p className="text-foreground">{findingText(t, f)}</p>
                    {action && (
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-xs font-semibold text-muted-foreground">{t("cluster.diagnose.suggested", "Suggested:")}</span>
                        {f.action === "FORCE_RELEASE" && canAct && data.lease ? (
                          <Button size="sm" variant="warning" onClick={() => setConfirmRelease(true)} data-testid="cluster-diagnosis-release">
                            {action}
                          </Button>
                        ) : f.action === "REPLAY" ? (
                          canAct ? (
                            <Button size="sm" variant="outline" onClick={() => onShowDeadLetters(data.conversationId)} data-testid="cluster-diagnosis-replay">
                              {action}
                            </Button>
                          ) : (
                            <span className="text-xs text-foreground">{action}</span>
                          )
                        ) : f.action === "OPEN_APPROVALS" ? (
                          <Link to="/manage/approvals" className="text-xs text-primary hover:underline">
                            {action}
                          </Link>
                        ) : f.action === "CANCEL" ? (
                          <Link to={`/manage/conversationview/${data.conversationId}`} className="text-xs text-primary hover:underline">
                            {action}
                          </Link>
                        ) : (
                          <span className="text-xs text-foreground">{action}</span>
                        )}
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}

      <AlertDialog
        open={confirmRelease}
        onOpenChange={setConfirmRelease}
        variant="warning"
        title={t("cluster.release.title", "Force-release the lease of {{id}}?", { id })}
        description={t(
          "cluster.release.text",
          "The lease held by {{node}} is deleted and the next turn of this conversation can run at once, on any node, with a newer fencing token. This is safe even if {{node}} is still alive: the fence refuses its late write and the turn is dead-lettered, where you can replay it. Nothing else changes.",
          { node: data?.lease?.holderNode ?? "" },
        )}
        confirmLabel={t("cluster.leases.release", "Release")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={doRelease}
        isPending={release.isPending}
      />
    </section>
  );
}
