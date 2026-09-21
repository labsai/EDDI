import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { useQuery } from "@tanstack/react-query";
import { History, Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { useConversationDescriptors } from "@/hooks/use-conversations";
import { isOperatorProbeConversation } from "@/hooks/use-operator-chat";
import {
  getSimpleConversationLog,
  extractInput,
  parseConversationUri,
  type ConversationState,
} from "@/lib/api/conversations";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";

/**
 * Past operator conversations, so an investigation stays reachable after the
 * tab that started it is gone.
 *
 * Capped rather than paged. This is a "find the thing I was doing" list, not an
 * audit surface — Conversations in the main nav already lists every
 * conversation on the platform with filters and paging, and duplicating that
 * here would be a second, worse copy of it. The cap is also what keeps the
 * per-row preview below affordable.
 */
const HISTORY_PAGE_SIZE = 15;

/**
 * How many descriptors to REQUEST in order to render {@link HISTORY_PAGE_SIZE}.
 *
 * The backend applies its limit before this component filters probe
 * conversations out and sorts, so asking for exactly 15 meant a run of probes
 * could occupy the whole page — in the worst case rendering an empty list while
 * real investigations sat just past the cut. Over-fetching and trimming after
 * the filter is one request either way.
 */
const HISTORY_FETCH_SIZE = 50;

interface OperatorHistoryProps {
  /** The operator agent whose conversations these are. */
  agentId: string;
  /** Highlighted as current, if it is in the list. */
  activeConversationId: string | null;
  onSelect: (conversationId: string) => void;
  /** True while a pick is being loaded — disables the list so a second click
   *  cannot start a competing load. */
  isLoading?: boolean;
}

/**
 * Human label for a lifecycle state.
 *
 * Reuses the keys the Conversations page already ships in all 11 locales rather
 * than minting an `operator.history.state.*` set — the same state under two
 * names in two places is how a UI ends up calling one thing "Ended" here and
 * something else there.
 */
function stateLabel(state: ConversationState, t: TFunction): string {
  switch (state) {
    case "READY":
      return t("conversations.stateActive", "Active");
    case "IN_PROGRESS":
      return t("conversations.stateInProgress", "In Progress");
    case "ENDED":
      return t("conversations.stateEnded", "Ended");
    case "EXECUTION_INTERRUPTED":
      return t("conversations.stateInterrupted", "Interrupted");
    case "AWAITING_HUMAN":
      return t("hitl.awaitingHuman", "Awaiting Human");
    case "ERROR":
      return t("status.error", "Error");
    default:
      return state;
  }
}

/** Badge variant for a conversation's lifecycle state. */
function stateVariant(state: ConversationState): "warning" | "destructive" | "secondary" | "outline" {
  switch (state) {
    // The one state that is a call to action rather than a status: this
    // conversation is holding a decision that nobody has made yet.
    case "AWAITING_HUMAN":
      return "warning";
    case "ERROR":
    case "EXECUTION_INTERRUPTED":
      return "destructive";
    case "ENDED":
      return "secondary";
    default:
      return "outline";
  }
}

export function OperatorHistory({
  agentId,
  activeConversationId,
  onSelect,
  isLoading,
}: OperatorHistoryProps) {
  const { t } = useTranslation();
  const { data, isLoading: listLoading, isError, error, refetch } = useConversationDescriptors(
    HISTORY_FETCH_SIZE,
    0,
    "",
    agentId,
  );

  if (listLoading) {
    return (
      <div className="space-y-2" role="status" aria-label={t("common.loading", "Loading...")}>
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-16 w-full rounded-xl" />
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <ErrorState
        message={getErrorMessage(error)}
        onRetry={() => void refetch()}
        retryLabel={t("common.retry", "Retry")}
      />
    );
  }

  // Probe conversations are activation's, not the admin's — see
  // isOperatorProbeConversation. A few reconfigures and "Check again" clicks
  // would otherwise evict real investigations from this capped page.
  const conversations = (data ?? []).filter((c) => !isOperatorProbeConversation(c));
  if (conversations.length === 0) {
    return (
      <EmptyState
        icon={History}
        title={t("operator.history.emptyTitle", "No past conversations")}
        description={t(
          "operator.history.emptyDescription",
          "Conversations you have with the operator show up here, so you can pick one back up later.",
        )}
      />
    );
  }

  // Newest first, sorted here rather than trusted from the endpoint: the
  // descriptor store's sort is a per-filter backend setting, not a documented
  // newest-first contract.
  // Sorted BEFORE trimming, so the cap keeps the newest 15 rather than whichever
  // 15 the backend's own (per-filter, not newest-first) sort happened to return.
  const sorted = [...conversations]
    .sort((a, b) => (b.lastModifiedOn ?? b.createdOn ?? 0) - (a.lastModifiedOn ?? a.createdOn ?? 0))
    .slice(0, HISTORY_PAGE_SIZE);

  return (
    <ul className="space-y-2" data-testid="operator-history-list">
      {sorted.map((conversation) => {
        const conversationId = parseConversationUri(conversation.resource);
        const isActive = conversationId === activeConversationId;
        const state = conversation.conversationState || "READY";
        return (
          <li key={conversation.resource}>
            <button
              type="button"
              onClick={() => onSelect(conversationId)}
              disabled={isLoading}
              aria-current={isActive ? "true" : undefined}
              className={cn(
                "w-full rounded-xl border border-border bg-card p-3 text-start transition-colors",
                "hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary",
                "disabled:cursor-not-allowed disabled:opacity-60",
                isActive && "border-primary bg-primary/5",
              )}
              data-testid={`operator-conversation-${conversationId}`}
            >
              <div className="flex items-start justify-between gap-3">
                <ConversationPreview conversationId={conversationId} />
                <Badge variant={stateVariant(state)} className="shrink-0">
                  {stateLabel(state, t)}
                </Badge>
              </div>
              <div className="mt-1.5 flex items-center gap-2 text-xs text-muted-foreground">
                <span>
                  {conversation.lastModifiedOn
                    ? new Date(conversation.lastModifiedOn).toLocaleString()
                    : "—"}
                </span>
                {conversation.conversationStepSize != null && (
                  <>
                    <span aria-hidden="true">·</span>
                    <span>
                      {t("operator.history.stepCount", "{{count}} step", {
                        count: conversation.conversationStepSize,
                      })}
                    </span>
                  </>
                )}
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * The conversation's opening question, which is what actually identifies it.
 *
 * Fetched per row because no descriptor field carries it — `name` resolves to
 * the agent's name, not the transcript — and a list of bare timestamps is not
 * something anyone can pick their own investigation out of. Same shape as the
 * conversations page's own per-row read (`StepCountBadge`), and the reason
 * {@link HISTORY_PAGE_SIZE} is small.
 *
 * Degrades to nothing on failure: the row still has its timestamp, state and
 * step count, and one unreadable transcript must not take the list down.
 */
function ConversationPreview({ conversationId }: { conversationId: string }) {
  const { t } = useTranslation();
  const { data, isLoading } = useQuery({
    queryKey: ["operator", "conversation-preview", conversationId],
    queryFn: async () => {
      const snapshot = await getSimpleConversationLog(conversationId, false, false);
      for (const step of snapshot.conversationSteps ?? []) {
        const input = extractInput(step);
        if (input) return input;
      }
      return "";
    },
    enabled: !!conversationId,
    staleTime: 5 * 60_000,
    retry: false,
  });

  if (isLoading) {
    return (
      <span className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-3 w-3 animate-spin" />
        {t("common.loading", "Loading...")}
      </span>
    );
  }

  return (
    <span className="line-clamp-2 text-sm font-medium text-foreground">
      {data || t("operator.history.noOpeningMessage", "(no message yet)")}
    </span>
  );
}
