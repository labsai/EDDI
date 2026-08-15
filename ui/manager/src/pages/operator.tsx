import { useState, useCallback, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  Sparkles,
  AlertTriangle,
  Loader2,
  PauseCircle,
  RefreshCw,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/shared/empty-state";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { OperatorActivation } from "@/components/operator/operator-activation";
import { OperatorChat } from "@/components/operator/operator-chat";
import { OperatorHistory } from "@/components/operator/operator-history";
import { OperatorStatusPanel } from "@/components/operator/operator-status";
import {
  useOperatorConfig,
  useOperatorStatus,
  useActivateOperator,
  useReactivateOperator,
  useDeactivateOperator,
  useResetOperator,
  useOperatorCanary,
  useVerifyOperatorGate,
  seedConfig,
  runPostActivationProbes,
  operatorKeys,
  type ActivationStage,
} from "@/hooks/use-operator";
import { useOperatorChat } from "@/hooks/use-operator-chat";
import { useApprovalStatus } from "@/hooks/use-hitl";
import { getErrorMessage } from "@/lib/api-client";
import { fetchOpenApiSpec, type OperatorConfig } from "@/lib/api/operator";
import { buildOperationIdIndex, reconstructEndpoint } from "@/lib/operator/reconstruct-endpoint";
import { findBlockedCalls } from "@/lib/operator/blocked-calls";
import { RequestPreview } from "@/components/operator/request-preview";
import type { PendingToolCallView } from "@/lib/api/hitl";

export function OperatorPage() {
  const { t } = useTranslation();
  const {
    data: config,
    isLoading,
    isError,
    error: configError,
    refetch,
  } = useOperatorConfig();

  const [showActivation, setShowActivation] = useState(false);
  const [stage, setStage] = useState<ActivationStage>("idle");
  const [activationError, setActivationError] = useState<string | null>(null);
  /**
   * Set when the operator deployed but could not actually read the platform.
   * Kept separate from `activationError`: the operator exists and is usable to
   * reconfigure, so this is a warning on the operator screen, not a failure
   * that sends the admin back to the form.
   */
  const [canaryWarning, setCanaryWarning] = useState<string | null>(null);
  /** Delete is irreversible, so it is confirmed wherever it is offered. */
  const [confirmPausedReset, setConfirmPausedReset] = useState(false);
  /** Chat or History. Local, not a route: switching tabs is not a navigation
   *  anyone wants in their back-button history mid-investigation. */
  const [tab, setTab] = useState<"chat" | "history">("chat");

  const activate = useActivateOperator();
  const reactivate = useReactivateOperator();
  const deactivate = useDeactivateOperator();
  const reset = useResetOperator();
  const canary = useOperatorCanary();

  const queryClient = useQueryClient();
  const status = useOperatorStatus(config);
  const gate = useVerifyOperatorGate(config);
  const chat = useOperatorChat(config);

  // Structured RULE/TOOL_CALL pause detail — the streamed `done` snapshot only
  // carries the generic bookmark fields, not per-call tool names/arguments.
  const approvalStatus = useApprovalStatus(chat.conversationId ?? undefined, chat.isPaused);

  /**
   * Bring the tab's conversation back on mount.
   *
   * The store is shared with the docked drawer, which hydrates too — `hydrate`
   * is idempotent and no-ops while one is in flight or a transcript is already
   * on screen, so both mounting at once loads it once. Firing on every render
   * where the identity changes is harmless for the same reason.
   */
  const isActiveOperator = Boolean(config?.enabled && config?.agentId);
  const hydrate = chat.hydrate;
  useEffect(() => {
    if (!isActiveOperator) return;
    void hydrate();
  }, [isActiveOperator, hydrate]);

  /**
   * True only while an explicit History pick is loading.
   *
   * Deliberately NOT `chat.isHydrating`, which is also true during the silent
   * mount restore — feeding that to the list disabled every row for a load that
   * had nothing to do with a pick, with no spinner to explain it.
   */
  const [isPicking, setIsPicking] = useState(false);

  const handleSelectConversation = useCallback(
    (conversationId: string) => {
      // Switch first: the load is what the Chat tab renders, and leaving the
      // admin on the list while it happens hides the thing they just asked for.
      setTab("chat");
      setIsPicking(true);
      void chat.selectConversation(conversationId).finally(() => setIsPicking(false));
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [chat.selectConversation],
  );

  // Fetched once a pause needs it, cached for the tab: reconstructing "METHOD
  // /path" for display is the same lookup on every pause, and the spec does
  // not change between them.
  const specQuery = useQuery({
    queryKey: ["operator", "openapi-spec-for-reconstruction"],
    queryFn: fetchOpenApiSpec,
    enabled: chat.isPaused,
    staleTime: Infinity,
  });
  const operationIdIndex = useMemo(
    () => (specQuery.data ? buildOperationIdIndex(specQuery.data) : {}),
    [specQuery.data],
  );
  /**
   * The writes this surface refuses outright rather than merely flagging — a
   * write aimed at the operator's OWN agent document, and an LLM-config write
   * that would set its own approval gate. See `blocked-calls.ts`, which resolves
   * both guards so all three approval surfaces refuse identically. Every other
   * write here is reviewable; these are the two that remove the reviewing.
   */
  const blockedCalls = useMemo(() => {
    const details = chat.isPaused ? approvalStatus.data?.pauseDetails : undefined;
    // Narrowed on the discriminator rather than a `"calls" in` probe: a RULE
    // pause has no per-call requests to target anything with.
    const pending = details?.type === "TOOL_CALL" ? details.calls : undefined;
    return findBlockedCalls(pending, config?.agentId, chat.conversationId, t);
  }, [chat.isPaused, approvalStatus.data, config?.agentId, chat.conversationId, t]);
  /**
   * Resolve the pause, then DROP the cached approval-status.
   *
   * `useApprovalStatus` is keyed on the conversation id alone, and a turn may
   * pause up to `maxPausesPerTurn` times (backend default 3). Without this, the
   * second pause of a conversation would render the FIRST pause's cached
   * `pauseDetails` — showing an approver a different set of tool calls than the
   * one actually awaiting their decision. Removing rather than invalidating so
   * the next pause starts at `undefined`, which drives `pauseDetailsPending`
   * and keeps Approve disabled until the real details arrive.
   *
   * (`useResumeConversation` does this invalidation itself, but this surface
   * calls `resumeConversation` directly — it also has to poll for the resumed
   * turn's outcome, which that mutation does not do.)
   */
  const handleDecide = useCallback<typeof chat.resolveApproval>(
    async (verdict, note, toolDecisions) => {
      const conversationId = chat.conversationId;
      try {
        await chat.resolveApproval(verdict, note, toolDecisions);
      } finally {
        if (conversationId) {
          queryClient.removeQueries({ queryKey: ["approval-status", conversationId] });
        }
      }
    },
    // `chat` is recreated each render; only these two members are used.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [chat.resolveApproval, chat.conversationId, queryClient],
  );

  const renderCallExtra = useCallback(
    (call: PendingToolCallView) => {
      // The backend's own resolved-request preview is ground truth — prefer it
      // over guessing an endpoint client-side from the tool name's operationId.
      // The client-side reconstruction remains only for a call the backend could
      // not preview (a non-http tool source, or a pre-fix persisted pause).
      if (call.requestPreview) {
        return (
          <RequestPreview preview={call.requestPreview} pinned={call.requestPinned} callId={call.callId} />
        );
      }
      const endpoint = reconstructEndpoint(call.toolName, operationIdIndex);
      if (!endpoint) return null;
      return (
        <p className="mb-1.5 font-mono text-[11px] text-muted-foreground" data-testid={`tool-endpoint-${call.callId}`}>
          {t("operator.approval.reconstructedEndpoint", "{{method}} {{path}} (reconstructed)", {
            method: endpoint.method,
            path: endpoint.path,
          })}
        </p>
      );
    },
    [operationIdIndex, t],
  );

  const handleActivate = useCallback(
    (next: OperatorConfig, apiKey: string, baseUrl?: string) => {
      setActivationError(null);
      activate.mutate(
        {
          agentName: "EDDI Platform Operator",
          config: next,
          apiKey,
          baseUrl,
          onStage: setStage,
        },
        {
          onSuccess: (outcome) => {
            setStage("idle");
            setShowActivation(false);
            // The predecessor agent was hard-deleted; its conversation id is dead.
            chat.reset();
            // Activation now ends at the deterministic checks — the operator is
            // live and usable RIGHT NOW. The LLM probes (read canary + live
            // write probe) verify in the background and report as they land.
            setCanaryWarning(null);
            toast.success(
              outcome.config.scope === "read_write" && outcome.policyVerified
                ? t("operator.toast.activatedGateVerified",
                    "Platform Operator activated — approval gate verified. Connection checks are running in the background.")
                : t("operator.toast.activatedChecking",
                    "Platform Operator activated. Connection checks are running in the background."),
            );
            void runPostActivationProbes(outcome, {
              onReadResult: (result) => {
                if (result.ok) {
                  setCanaryWarning(null);
                } else {
                  setCanaryWarning(
                    result.error ?? t("operator.canary.genericFailure", "The connection check did not succeed."),
                  );
                  toast.warning(
                    t("operator.toast.activatedButUnreachable", "Operator deployed, but it could not read your platform"),
                  );
                }
              },
              onWriteResult: (report) => {
                if (report.result.outcome === "pass") {
                  toast.success(
                    t("operator.toast.writeProbeVerified", "Write access verified — a real gated write paused for approval."),
                  );
                  return;
                }
                if (report.tornDown || report.result.outcome === "fail") {
                  // Proven breach (or a breach whose teardown failed): this is
                  // a failure state, not a warning — surface it where a failed
                  // activation would land and re-read what the server now has.
                  // The toast carries the report's OWN disposition: a failed
                  // teardown says "still deployed — remove it manually", and a
                  // fixed "was removed" here would falsely reassure the admin
                  // (this toast can be the only visible result after
                  // navigation, since the activation form is already closed).
                  const message =
                    report.message ??
                    t("operator.toast.writeProbeFailed", "The approval gate did not hold — the operator was removed.");
                  setActivationError(message);
                  toast.error(message);
                  void queryClient.invalidateQueries({ queryKey: operatorKeys.all });
                  return;
                }
                // Inconclusive with the gate already verified deterministically:
                // the model declining an unexplained write is its hardening
                // working, and a warning toast on every activation trains admins
                // to dismiss operator warnings. Log it for the curious; say
                // nothing on screen.
                if (report.quiet) {
                  console.info("[operator] write probe inconclusive:", report.message);
                  return;
                }
                // Inconclusive AND no deterministic verdict to fall back on —
                // the probe was the only signal there was, so it stays a warning.
                toast.warning(
                  report.message ??
                    t("operator.toast.writeProbeInconclusive", "The live write probe was inconclusive."),
                );
              },
            });
          },
          onError: (err) => {
            setStage("idle");
            setActivationError(getErrorMessage(err));
          },
        },
      );
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [activate, chat.reset, t],
  );

  const handleReactivate = useCallback(() => {
    if (!config) return;
    reactivate.mutate(config, {
      onSuccess: () => toast.success(t("operator.toast.activated", "Platform Operator activated")),
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }, [config, reactivate, t]);

  const handleRecheck = useCallback(() => {
    if (!config) return;
    canary.mutate(config, {
      onSuccess: (result) => {
        if (result.ok) {
          setCanaryWarning(null);
          toast.success(t("operator.canary.passed", "The operator can reach your platform."));
        } else {
          setCanaryWarning(result.error ?? t("operator.canary.genericFailure", "The connection check did not succeed."));
        }
      },
      onError: (err) => setCanaryWarning(getErrorMessage(err)),
    });
  }, [config, canary, t]);

  const handleDeactivate = useCallback(() => {
    if (!config) return;
    deactivate.mutate(config, {
      onSuccess: () => {
        chat.reset();
        setCanaryWarning(null);
        toast.success(t("operator.toast.deactivated", "Platform Operator deactivated"));
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
    // `chat` is recreated each render; only `chat.reset` is stable and used.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, deactivate, chat.reset, t]);

  const handleReset = useCallback(() => {
    if (!config) return;
    reset.mutate(config, {
      onSuccess: () => {
        chat.reset();
        setCanaryWarning(null);
        setShowActivation(false);
        toast.success(t("operator.toast.reset", "Platform Operator deleted"));
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, reset, chat.reset, t]);

  if (isLoading) {
    return (
      <div
        className="flex items-center justify-center py-24 text-muted-foreground"
        role="status"
        aria-label={t("common.loading", "Loading...")}
      >
        <Loader2 className="h-6 w-6 animate-spin" />
      </div>
    );
  }

  // A failed config read is not the same as "never activated" — an operator may
  // be running while the variable store is briefly unreachable, and offering
  // activation here would invite provisioning a duplicate.
  if (isError) {
    return (
      <div className="mx-auto max-w-lg space-y-4 py-16 text-center">
        <AlertTriangle className="mx-auto h-10 w-10 text-destructive" />
        <p className="font-medium">{t("operator.configError", "Couldn't load the operator configuration")}</p>
        <p className="text-sm text-muted-foreground">{getErrorMessage(configError)}</p>
        <Button onClick={() => refetch()} data-testid="operator-config-retry">
          {t("common.retry", "Retry")}
        </Button>
      </div>
    );
  }

  const isActive = Boolean(config?.enabled && config?.agentId);
  // Configured but switched off: the agent and its resources still exist, so
  // this is a pause to undo, not a setup to redo.
  const isPaused = Boolean(config?.agentId && config.version != null && !config.enabled);

  if (showActivation) {
    return (
      <div className="py-8">
        <OperatorActivation
          initial={seedConfig(config)}
          stage={stage}
          error={activationError}
          onActivate={handleActivate}
          onCancel={() => {
            setShowActivation(false);
            setActivationError(null);
          }}
        />
      </div>
    );
  }

  if (isPaused) {
    return (
      <div className="mx-auto max-w-lg space-y-4 py-16 text-center">
        <PauseCircle className="mx-auto h-10 w-10 text-muted-foreground/60" />
        <p className="text-lg font-medium">{t("operator.paused.title", "The Platform Operator is paused")}</p>
        <p className="text-sm text-muted-foreground">{t("operator.paused.description", "It is still configured — turning it back on redeploys the same agent. Nothing has been deleted.")}</p>
        <div className="flex justify-center gap-2 pt-2">
          <Button
            onClick={handleReactivate}
            disabled={reactivate.isPending}
            data-testid="operator-reactivate"
          >
            {reactivate.isPending && <Loader2 className="me-2 h-4 w-4 animate-spin" />}
            {t("operator.paused.action", "Turn back on")}
          </Button>
          <Button variant="outline" onClick={() => setShowActivation(true)}>
            {t("operator.status.reconfigure", "Reconfigure")}
          </Button>
          <Button
            variant="ghost"
            onClick={() => setConfirmPausedReset(true)}
            disabled={reset.isPending}
            data-testid="operator-reset"
          >
            {t("operator.status.reset", "Delete operator")}
          </Button>
        </div>

        <AlertDialog
          open={confirmPausedReset}
          onOpenChange={setConfirmPausedReset}
          title={t("operator.status.resetConfirmTitle", "Delete the Platform Operator?")}
          description={t("operator.status.resetConfirmBody", "The operator agent and all the resources created for it are permanently deleted, along with its saved configuration. This cannot be undone.")}
          confirmLabel={t("operator.status.reset", "Delete operator")}
          cancelLabel={t("common.cancel", "Cancel")}
          variant="destructive"
          onConfirm={() => {
            setConfirmPausedReset(false);
            handleReset();
          }}
        />
      </div>
    );
  }

  if (!isActive) {
    return (
      <div className="py-8">
        <EmptyState
          icon={Sparkles}
          title={t("operator.empty.title", "The Platform Operator is off")}
          description={t("operator.empty.description", "Turn it on to chat with an agent that can inspect your agents, workflows, conversations, deployments and logs — and, with your approval of each change, operate them for you.")}
          actionLabel={t("operator.empty.action", "Activate the Platform Operator")}
          onAction={() => setShowActivation(true)}
        />
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 py-4">
      <header className="flex items-center gap-3">
        <Sparkles className="h-6 w-6 text-primary" />
        <div>
          <h1 className="text-xl font-semibold">{t("operator.title", "Platform Operator")}</h1>
          <p className="text-sm text-muted-foreground">{t("operator.subtitle", "Ask about this EDDI deployment — it looks things up for you.")}</p>
        </div>
      </header>

      {/* Deployed is not the same as working. When the probe read failed, say so
          here rather than letting a green status badge imply everything is fine. */}
      {canaryWarning && (
        <div
          className="flex flex-wrap items-start gap-3 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-400"
          role="alert"
          data-testid="operator-canary-warning"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="flex-1">
            <strong className="font-medium">{t("operator.canary.title", "The operator deployed, but could not read your platform.")}</strong>{" "}
            {canaryWarning}
          </span>
          <Button
            size="sm"
            variant="outline"
            onClick={handleRecheck}
            disabled={canary.isPending}
            data-testid="operator-canary-recheck"
          >
            {canary.isPending ? (
              <Loader2 className="me-2 h-3 w-3 animate-spin" />
            ) : (
              <RefreshCw className="me-2 h-3 w-3" />
            )}
            {t("operator.canary.recheck", "Check again")}
          </Button>
        </div>
      )}

      {/* A real ARIA tablist: roving tabIndex, arrow-key selection, and each tab
          bound to its panel — the pattern `config-editor-layout.tsx` already
          establishes. Declaring role="tab" without them looks correct to a
          checker and leaves keyboard and screen-reader users with two buttons
          that announce as tabs and behave as neither. */}
      <div
        role="tablist"
        aria-label={t("operator.tabs.label", "Operator views")}
        className="flex items-center gap-1 border-b border-border"
        onKeyDown={(e) => {
          if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
          e.preventDefault();
          const next = tab === "chat" ? "history" : "chat";
          setTab(next);
          // Focus follows selection, as the APG pattern specifies for
          // automatically-activated tabs.
          requestAnimationFrame(() => document.getElementById(`operator-tab-${next}`)?.focus());
        }}
      >
        {(["chat", "history"] as const).map((value) => (
          <button
            key={value}
            id={`operator-tab-${value}`}
            type="button"
            role="tab"
            aria-selected={tab === value}
            aria-controls={`operator-tabpanel-${value}`}
            tabIndex={tab === value ? 0 : -1}
            onClick={() => setTab(value)}
            className={
              tab === value
                ? "-mb-px border-b-2 border-primary px-3 py-2 text-sm font-medium text-foreground"
                : "-mb-px border-b-2 border-transparent px-3 py-2 text-sm font-medium text-muted-foreground hover:text-foreground"
            }
            data-testid={`operator-tab-${value}`}
          >
            {value === "chat"
              ? t("operator.tabs.chat", "Chat")
              : t("operator.tabs.history", "History")}
          </button>
        ))}
      </div>

      {/* The status panel stays put across both tabs — deployment state and the
          gate verdict are exactly the context someone reviewing past
          conversations wants, and moving it would make the tabs feel like two
          different screens. Only the left cell swaps. */}
      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[1fr_20rem]">
        {/* Rendered only while showing. `hidden` is display:none, which does NOT
            unmount — the list and one full-transcript read per row fired on
            every operator page load for a tab nobody had opened (measured: 17
            requests). Unlike the chat below there is nothing here worth keeping
            alive: react-query caches the data, so coming back is free. */}
        {tab === "history" && (
          <div
            id="operator-tabpanel-history"
            role="tabpanel"
            aria-labelledby="operator-tab-history"
            tabIndex={0}
            className="min-h-0 min-w-0 overflow-y-auto"
          >
            <OperatorHistory
              agentId={config!.agentId!}
              activeConversationId={chat.conversationId}
              onSelect={handleSelectConversation}
              isLoading={isPicking}
            />
          </div>
        )}

        {/* Kept MOUNTED while History is showing, not unmounted and rebuilt.
            OperatorChat owns scroll position and composer draft state, and a
            streaming turn keeps running while the admin looks through history —
            a remount would drop the draft and jump the transcript to the top. */}
        {/* min-w-0 belongs on the GRID ITEM, which is now this wrapper rather
            than OperatorChat itself. Without it the track grows to the widest
            unbreakable line inside (an approval card's one-line JSON args) and
            pushes the page into horizontal scroll — the child's own min-w-0 is a
            floor, not a ceiling, so it no longer constrains the track. */}
        <div
          id="operator-tabpanel-chat"
          role="tabpanel"
          aria-labelledby="operator-tab-chat"
          className={tab === "chat" ? "flex min-h-0 min-w-0 flex-col" : "hidden"}
        >
        <OperatorChat
          messages={chat.messages}
          events={chat.events}
          liveToolCalls={chat.liveToolCalls}
          liveToolsSettled={chat.liveToolsSettled}
          tracesByMessageId={chat.tracesByMessageId}
          isStreaming={chat.isStreaming}
          error={chat.error}
          onSend={(input, attachments) => chat.send(input, undefined, attachments)}
          onStop={chat.stop}
          onReset={chat.reset}
          conversationId={chat.conversationId}
          onEnsureConversation={chat.ensureConversation}
          isPaused={chat.isPaused}
          // approval-status first: the chat hook derives its own pauseReason from
          // getSimpleConversationLog, which does not carry one — so on the 409 and
          // re-pause paths it is always null. This endpoint is the one that has it,
          // along with the timeout fields the countdown needs.
          pauseReason={approvalStatus.data?.pauseReason ?? chat.pauseReason}
          pausedAt={approvalStatus.data?.pausedAt}
          timeoutPolicy={approvalStatus.data?.timeoutPolicy}
          approvalTimeout={approvalStatus.data?.approvalTimeout}
          pauseDetails={chat.isPaused ? approvalStatus.data?.pauseDetails : undefined}
          pauseDetailsPending={chat.isPaused && approvalStatus.isLoading}
          pauseDetailsError={chat.isPaused && approvalStatus.isError}
          onRetryPauseDetails={() => void approvalStatus.refetch()}
          isResolvingPause={chat.isResolvingPause}
          resolveError={chat.resolveError}
          onDecide={handleDecide}
          blockedCalls={blockedCalls}
          renderCallExtra={renderCallExtra}
          isVisible={tab === "chat"}
          isRestoring={isPicking}
          isReadOnly={chat.isReadOnly}
        />
        </div>
        <OperatorStatusPanel
          config={config!}
          status={status.data}
          statusLoading={status.isLoading}
          gate={gate.data}
          gateLoading={gate.isLoading}
          onReconfigure={() => setShowActivation(true)}
          onDeactivate={handleDeactivate}
          onReset={handleReset}
          onRecheck={handleRecheck}
          recheckPending={canary.isPending}
          busy={deactivate.isPending || reset.isPending}
        />
      </div>
    </div>
  );
}
