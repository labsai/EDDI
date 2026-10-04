import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { ChevronLeft, ChevronRight, Inbox, Lock, RotateCcw, Trash2 } from "lucide-react";
import type { BulkResult, DeadLetterFilter, DeadLetterView } from "@/lib/api/cluster";
import { deadLetterReasonHelp, deadLetterReasonLabel, notReplayableLabel, outcomeLabel } from "@/lib/cluster-labels";
import { useClusterDeadLetters, useDeadLetterSummary, useDiscardDeadLetters, useReplayDeadLetters } from "@/hooks/use-cluster";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { ClusterDrawer } from "./cluster-drawer";

const PERIODS: Record<string, number | undefined> = { all: undefined, "1h": 3_600_000, "24h": 86_400_000, "7d": 604_800_000 };

function reasonVariant(reason: string): "destructive" | "warning" | "secondary" {
  return reason === "fenced" ? "warning" : reason === "timeout" ? "secondary" : "destructive";
}

/** Read-only roles see counts only: the entries carry what users typed. */
export function DeadLetterCountsCard({ onOpen }: { onOpen?: () => void }) {
  const { t } = useTranslation();
  const { data, isError } = useDeadLetterSummary();
  return (
    <section className="rounded-xl border border-border bg-card p-5" aria-labelledby="cluster-dl-summary-heading" data-testid="coordinator-dead-letters">
      <div className="flex items-center justify-between gap-2">
        <h2 id="cluster-dl-summary-heading" className="flex items-center gap-2 text-lg font-semibold text-foreground">
          <Inbox className="h-5 w-5 text-primary" aria-hidden="true" />
          {t("cluster.dl.title", "Dead letters")}
        </h2>
        {onOpen && (
          <Button size="sm" variant="outline" onClick={onOpen} data-testid="cluster-dl-open">
            {t("cluster.dl.open", "Inspect")}
          </Button>
        )}
      </div>
      {isError && !data ? (
        <p className="mt-2 text-sm text-muted-foreground">{t("common.error", "Something went wrong")}</p>
      ) : !data ? (
        <Skeleton className="mt-3 h-6 w-40" />
      ) : data.total === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground" data-testid="cluster-dl-none">
          {t("cluster.dl.none", "None waiting — every failed turn has been replayed or discarded.")}
        </p>
      ) : (
        <div className="mt-2 space-y-2 text-sm">
          <p className="text-foreground" data-testid="cluster-dl-total">
            {t("cluster.dl.waiting", "{{n}} waiting", { n: data.total })}
            {data.local > 0 && (
              <span className="text-warning"> · {t("cluster.dl.localCount", "{{n}} kept on nodes without NATS", { n: data.local })}</span>
            )}
          </p>
          <ul className="flex flex-wrap gap-1.5">
            {Object.entries(data.byReason).map(([reason, n]) => (
              <li key={reason}>
                <Badge variant={reasonVariant(reason)} data-testid={`cluster-dl-reason-${reason}`}>
                  {deadLetterReasonLabel(t, reason)}: {n}
                </Badge>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

/**
 * "What went wrong, and what can I safely do about it?" — the dead letters with
 * filters, a detail drawer, and replay/discard for one entry or a selection.
 */
export function ClusterDeadLettersPanel({ nodes, initialConversationId = "" }: { nodes: string[]; initialConversationId?: string }) {
  const { t } = useTranslation();
  const [reason, setReason] = useState("");
  const [nodeId, setNodeId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [conversationId, setConversationId] = useState(initialConversationId);
  const [period, setPeriod] = useState("all");
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [detail, setDetail] = useState<DeadLetterView | null>(null);
  const [confirm, setConfirm] = useState<{ action: "replay" | "discard"; ids: string[] } | null>(null);
  const [result, setResult] = useState<{ action: "replay" | "discard"; result: BulkResult } | null>(null);

  const filter: DeadLetterFilter = useMemo(() => {
    const span = PERIODS[period];
    return {
      reason: reason || undefined,
      nodeId: nodeId || undefined,
      agentId: agentId.trim() || undefined,
      conversationId: conversationId.trim() || undefined,
      // Rounded to the minute so the query key — and the page — stay stable between refreshes.
      from: span ? Math.floor((Date.now() - span) / 60_000) * 60_000 : undefined,
    };
  }, [reason, nodeId, agentId, conversationId, period]);
  const after = cursors[cursors.length - 1] ?? null;
  const { data, isLoading, isError, refetch } = useClusterDeadLetters(filter, after);
  const replay = useReplayDeadLetters();
  const discard = useDiscardDeadLetters();

  const resetPaging = () => {
    setCursors([null]);
    setSelected(new Set());
  };
  const entries = data?.entries ?? [];
  const allSelected = entries.length > 0 && entries.every((e) => selected.has(e.id));
  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const run = () => {
    if (!confirm) return;
    const mutation = confirm.action === "replay" ? replay : discard;
    mutation.mutate(confirm.ids, {
      onSuccess: (r) => {
        setResult({ action: confirm.action, result: r });
        setSelected(new Set());
        setConfirm(null);
        setDetail(null);
        if (r.failed === 0) toast.success(t("cluster.dl.allDone", "Done: {{ok}} of {{n}}", { ok: r.succeeded, n: r.results.length }));
        else toast.warning(t("cluster.dl.someFailed", "{{ok}} of {{n}} done — see the outcome of each below", { ok: r.succeeded, n: r.results.length }));
      },
      onError: (e) => {
        toast.error(e instanceof Error ? e.message : String(e));
        setConfirm(null);
      },
    });
  };

  const selectedReplayable = entries.filter((e) => selected.has(e.id) && e.replayable).length;

  return (
    <section className="space-y-4" aria-labelledby="cluster-dl-heading" data-testid="cluster-dead-letters">
      <h2 id="cluster-dl-heading" className="flex items-center gap-2 text-lg font-semibold text-foreground">
        <Inbox className="h-5 w-5 text-primary" aria-hidden="true" />
        {t("cluster.dl.title", "Dead letters")}
      </h2>
      <p className="text-sm text-muted-foreground">
        {t(
          "cluster.dl.explain",
          "Turns that failed after they started. Replaying submits the captured input as a NEW turn of the conversation — its side effects run again — so replay only once the cause is fixed. Discarding is permanent.",
        )}
      </p>

      <fieldset className="grid gap-2 sm:grid-cols-2 lg:grid-cols-5" data-testid="cluster-dl-filters">
        <legend className="sr-only">{t("cluster.dl.filters", "Filters")}</legend>
        <select
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground"
          value={reason}
          onChange={(e) => {
            setReason(e.target.value);
            resetPaging();
          }}
          aria-label={t("cluster.dl.filterReason", "Reason")}
          data-testid="cluster-dl-filter-reason"
        >
          <option value="">{t("cluster.dl.anyReason", "Any reason")}</option>
          <option value="fenced">{deadLetterReasonLabel(t, "fenced")}</option>
          <option value="timeout">{deadLetterReasonLabel(t, "timeout")}</option>
          <option value="failed">{deadLetterReasonLabel(t, "failed")}</option>
        </select>
        <select
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground"
          value={nodeId}
          onChange={(e) => {
            setNodeId(e.target.value);
            resetPaging();
          }}
          aria-label={t("cluster.dl.filterNode", "Node")}
          data-testid="cluster-dl-filter-node"
        >
          <option value="">{t("cluster.dl.anyNode", "Any node")}</option>
          {nodes.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
        <Input
          value={agentId}
          onChange={(e) => {
            setAgentId(e.target.value);
            resetPaging();
          }}
          placeholder={t("cluster.dl.filterAgent", "Agent id")}
          aria-label={t("cluster.dl.filterAgent", "Agent id")}
          data-testid="cluster-dl-filter-agent"
        />
        <Input
          value={conversationId}
          onChange={(e) => {
            setConversationId(e.target.value);
            resetPaging();
          }}
          placeholder={t("cluster.dl.filterConversation", "Conversation id")}
          aria-label={t("cluster.dl.filterConversation", "Conversation id")}
          data-testid="cluster-dl-filter-conversation"
        />
        <select
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground"
          value={period}
          onChange={(e) => {
            setPeriod(e.target.value);
            resetPaging();
          }}
          aria-label={t("cluster.dl.filterTime", "Time")}
          data-testid="cluster-dl-filter-time"
        >
          <option value="all">{t("cluster.dl.anyTime", "Any time")}</option>
          <option value="1h">{t("cluster.dl.lastHour", "Last hour")}</option>
          <option value="24h">{t("cluster.dl.lastDay", "Last 24 hours")}</option>
          <option value="7d">{t("cluster.dl.lastWeek", "Last 7 days")}</option>
        </select>
      </fieldset>

      {selected.size > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/20 bg-primary/5 p-3" role="region" aria-label={t("cluster.dl.bulk", "Selected entries")} data-testid="cluster-dl-bulkbar">
          <span className="text-sm text-foreground">{t("cluster.dl.selected", "{{n}} selected", { n: selected.size })}</span>
          <Button
            size="sm"
            disabled={selectedReplayable === 0}
            onClick={() => setConfirm({ action: "replay", ids: entries.filter((e) => selected.has(e.id) && e.replayable).map((e) => e.id) })}
            data-testid="cluster-dl-bulk-replay"
          >
            <RotateCcw aria-hidden="true" />
            {t("cluster.dl.replaySelected", "Replay {{n}}", { n: selectedReplayable })}
          </Button>
          <Button size="sm" variant="destructive" onClick={() => setConfirm({ action: "discard", ids: [...selected] })} data-testid="cluster-dl-bulk-discard">
            <Trash2 aria-hidden="true" />
            {t("cluster.dl.discardSelected", "Discard {{n}}", { n: selected.size })}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
            {t("cluster.dl.clearSelection", "Clear selection")}
          </Button>
        </div>
      )}

      {result && (
        <div className="rounded-lg border border-border bg-card p-3" role="status" data-testid="cluster-dl-result">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-sm font-semibold text-foreground">
              {result.action === "replay"
                ? t("cluster.dl.replayResult", "Replay: {{ok}} of {{n}} replayed", { ok: result.result.succeeded, n: result.result.results.length })
                : t("cluster.dl.discardResult", "Discard: {{ok}} of {{n}} discarded", { ok: result.result.succeeded, n: result.result.results.length })}
            </p>
            <Button size="sm" variant="ghost" onClick={() => setResult(null)}>
              {t("common.close", "Close")}
            </Button>
          </div>
          <ul className="space-y-1 text-sm">
            {result.result.results.map((r) => (
              <li key={r.id} className="flex flex-wrap gap-2" data-testid={`cluster-dl-outcome-${r.id}`} data-outcome={r.outcome}>
                <span className="font-mono text-xs">#{r.id}</span>
                <Badge variant={r.outcome === "REPLAYED" || r.outcome === "DISCARDED" ? "success" : r.outcome === "NOT_FOUND" ? "secondary" : "warning"}>
                  {outcomeLabel(t, r.outcome)}
                </Badge>
                {r.message && (
                  <span className="text-muted-foreground">{r.outcome === "NOT_REPLAYABLE" ? notReplayableLabel(t, r.message) : r.message}</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {isLoading ? (
        <div className="space-y-2" data-testid="cluster-dl-loading">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-12 w-full" />
          ))}
        </div>
      ) : isError && !data ? (
        <div data-testid="dead-letters-error">
          <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => refetch()} retryLabel={t("common.retry", "Retry")} />
        </div>
      ) : entries.length === 0 ? (
        <div data-testid="dead-letters-empty">
          <EmptyState
            icon={Inbox}
            title={
              reason || nodeId || agentId || conversationId || period !== "all"
                ? t("common.noResults", "No results found")
                : t("cluster.dl.empty", "No dead letters")
            }
            description={data && data.scanned > 0 && data.nextCursor ? t("cluster.dl.scanned", "{{n}} entries searched so far", { n: data.scanned }) : undefined}
          />
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border/50">
          <table className="w-full text-sm" data-testid="dead-letters-table">
            <thead>
              <tr className="border-b border-border bg-muted/50 text-muted-foreground">
                <th className="w-10 px-3 py-3">
                  <input
                    type="checkbox"
                    checked={allSelected}
                    onChange={() => setSelected(allSelected ? new Set() : new Set(entries.map((e) => e.id)))}
                    aria-label={t("cluster.dl.selectAll", "Select all on this page")}
                    data-testid="cluster-dl-select-all"
                  />
                </th>
                <th className="px-3 py-3 text-start font-medium">{t("cluster.dl.colWhen", "When")}</th>
                <th className="px-3 py-3 text-start font-medium">{t("cluster.dl.colReason", "Reason")}</th>
                <th className="px-3 py-3 text-start font-medium">{t("cluster.dl.colConversation", "Conversation")}</th>
                <th className="px-3 py-3 text-start font-medium">{t("cluster.dl.colNode", "Node")}</th>
                <th className="px-3 py-3 text-start font-medium">{t("cluster.dl.colError", "Error")}</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((e) => (
                <tr key={e.id} className="border-b border-border/30 hover:bg-muted/30" data-testid={`cluster-dl-row-${e.id}`}>
                  <td className="px-3 py-2">
                    <input
                      type="checkbox"
                      checked={selected.has(e.id)}
                      onChange={() => toggle(e.id)}
                      aria-label={t("cluster.dl.select", "Select entry {{id}}", { id: e.id })}
                      data-testid={`cluster-dl-select-${e.id}`}
                    />
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 tabular-nums text-muted-foreground">{new Date(e.timestamp).toLocaleString()}</td>
                  <td className="px-3 py-2">
                    <Badge variant={reasonVariant(e.reason)}>{deadLetterReasonLabel(t, e.reason)}</Badge>
                    {!e.replayable && <Lock className="ms-1 inline h-3.5 w-3.5 text-muted-foreground" aria-label={t("cluster.dl.notReplayableShort", "Not replayable")} />}
                  </td>
                  <td className="max-w-[12rem] truncate px-3 py-2 font-mono text-xs">{e.conversationId}</td>
                  <td className="px-3 py-2 font-mono text-xs">{e.nodeId ?? "—"}</td>
                  <td className="max-w-[20rem] px-3 py-2">
                    <button
                      className="w-full truncate text-start text-foreground underline-offset-2 hover:underline"
                      onClick={() => setDetail(e)}
                      title={e.error ?? undefined}
                      data-testid={`cluster-dl-open-${e.id}`}
                    >
                      {e.error}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex items-center justify-between">
        <Button
          size="sm"
          variant="outline"
          disabled={cursors.length <= 1}
          onClick={() => {
            setCursors((c) => c.slice(0, -1));
            setSelected(new Set());
          }}
          data-testid="cluster-dl-prev"
        >
          <ChevronLeft className="rtl:rotate-180" aria-hidden="true" />
          {t("cluster.dl.prev", "Previous")}
        </Button>
        <span className="text-xs text-muted-foreground">{t("cluster.dl.page", "Page {{n}}", { n: cursors.length })}</span>
        <Button
          size="sm"
          variant="outline"
          disabled={!data?.nextCursor}
          onClick={() => {
            setCursors((c) => [...c, data!.nextCursor]);
            setSelected(new Set());
          }}
          data-testid="cluster-dl-next"
        >
          {t("cluster.dl.next", "Next")}
          <ChevronRight className="rtl:rotate-180" aria-hidden="true" />
        </Button>
      </div>

      <ClusterDrawer
        open={detail !== null}
        onClose={() => setDetail(null)}
        title={t("cluster.dl.detailTitle", "Dead letter #{{id}}", { id: detail?.id ?? "" })}
        testId="cluster-dl-drawer"
      >
        {detail && (
          <DeadLetterDetail
            entry={detail}
            onReplay={() => setConfirm({ action: "replay", ids: [detail.id] })}
            onDiscard={() => setConfirm({ action: "discard", ids: [detail.id] })}
          />
        )}
      </ClusterDrawer>

      <AlertDialog
        open={confirm !== null}
        onOpenChange={(o) => !o && setConfirm(null)}
        variant={confirm?.action === "discard" ? "destructive" : "warning"}
        title={
          confirm?.action === "replay"
            ? t("cluster.dl.replayTitle", "Replay {{n}} dead letters as new turns?", { n: confirm?.ids.length ?? 0 })
            : t("cluster.dl.discardTitle", "Discard {{n}} dead letters permanently?", { n: confirm?.ids.length ?? 0 })
        }
        description={
          confirm?.action === "replay"
            ? t(
                "cluster.dl.replayText",
                "Each captured input is sent again as a new turn of its conversation, as you, with replayOf set. Its tools and side effects run again. An entry is removed only once its turn was accepted; the outcome of each is shown afterwards.",
              )
            : t("cluster.dl.discardText", "The entries are removed from the shared stream for every node. They cannot be replayed afterwards.")
        }
        confirmLabel={confirm?.action === "replay" ? t("cluster.dl.replay", "Replay") : t("cluster.dl.discard", "Discard")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={run}
        isPending={replay.isPending || discard.isPending}
      />
    </section>
  );
}

function DeadLetterDetail({ entry, onReplay, onDiscard }: { entry: DeadLetterView; onReplay: () => void; onDiscard: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-5 text-sm" data-testid="cluster-dl-detail">
      <div className="space-y-1">
        <Badge variant={reasonVariant(entry.reason)}>{deadLetterReasonLabel(t, entry.reason)}</Badge>
        <p className="text-foreground" data-testid="cluster-dl-why">
          {deadLetterReasonHelp(t, entry)}
        </p>
      </div>
      <dl className="grid grid-cols-2 gap-3">
        <div className="col-span-2">
          <dt className="text-xs text-muted-foreground">{t("cluster.dl.colConversation", "Conversation")}</dt>
          <dd>
            <Link to={`/manage/conversationview/${entry.conversationId}`} className="font-mono text-xs text-primary hover:underline" data-testid="cluster-dl-conversation-link">
              {entry.conversationId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">{t("cluster.dl.colAgent", "Agent")}</dt>
          <dd className="font-mono text-xs">
            {entry.agentId ?? "—"}
            {entry.agentVersion != null ? ` v${entry.agentVersion}` : ""}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">{t("cluster.dl.colNode", "Node")}</dt>
          <dd className="font-mono text-xs">{entry.nodeId ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">{t("cluster.dl.colWhen", "When")}</dt>
          <dd className="tabular-nums">{new Date(entry.timestamp).toLocaleString()}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">{t("cluster.dl.environment", "Environment")}</dt>
          <dd>{entry.environment ?? "—"}</dd>
        </div>
        {entry.fence && (
          <div className="col-span-2" data-testid="cluster-dl-fence">
            <dt className="text-xs text-muted-foreground">{t("cluster.dl.fence", "Fencing tokens")}</dt>
            <dd className="font-mono text-xs">
              {t("cluster.dl.fenceValue", "refused write {{token}} < conversation {{stored}}", {
                token: entry.fence.token ?? "?",
                stored: entry.fence.storedFence ?? "?",
              })}
            </dd>
          </div>
        )}
      </dl>
      <div>
        <h3 className="text-xs text-muted-foreground">{t("cluster.dl.colError", "Error")}</h3>
        <pre className="mt-1 whitespace-pre-wrap break-words rounded-lg bg-muted/50 p-3 text-xs text-foreground">{entry.error}</pre>
      </div>
      <div>
        <h3 className="text-xs text-muted-foreground">{t("cluster.dl.input", "Input of the turn")}</h3>
        {entry.secretInput ? (
          <p className="mt-1 flex items-center gap-2 rounded-lg bg-muted/50 p-3 text-xs text-muted-foreground" data-testid="cluster-dl-input-secret">
            <Lock className="h-3.5 w-3.5" aria-hidden="true" />
            {t("cluster.dl.secret", "•••••• — marked secret by the client, never stored")}
          </p>
        ) : entry.input != null ? (
          <pre className="mt-1 whitespace-pre-wrap break-words rounded-lg bg-muted/50 p-3 text-xs text-foreground" data-testid="cluster-dl-input">
            {entry.input}
          </pre>
        ) : (
          <p className="mt-1 text-xs text-muted-foreground">—</p>
        )}
      </div>
      {!entry.replayable && (
        <p className="rounded-lg border border-border bg-muted/30 p-3 text-xs text-foreground" data-testid="cluster-dl-not-replayable">
          {notReplayableLabel(t, entry.notReplayableReason)}
        </p>
      )}
      <div className="flex gap-2 border-t border-border pt-4">
        <Button onClick={onReplay} disabled={!entry.replayable} data-testid="cluster-dl-replay">
          <RotateCcw aria-hidden="true" />
          {t("cluster.dl.replay", "Replay")}
        </Button>
        <Button variant="destructive" onClick={onDiscard} data-testid="cluster-dl-discard">
          <Trash2 aria-hidden="true" />
          {t("cluster.dl.discard", "Discard")}
        </Button>
      </div>
    </div>
  );
}
