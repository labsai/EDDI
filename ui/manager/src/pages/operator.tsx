import { useState, useCallback, useMemo } from "react";
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
    return findBlockedCalls(pending, config?.agentId, t);
  }, [chat.isPaused, approvalStatus.data, config?.agentId, t]);
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
            if (outcome.canary.ok) {
              setCanaryWarning(null);
              // A reachable onSuccess means the write canary either did not run
              // (read_only) or passed — a non-"pass" result throws, landing in
              // onError below with the agent already rolled back.
              toast.success(
                outcome.writeCanary
                  ? t("operator.toast.activatedReadWrite", "Platform Operator activated — write access verified")
                  : t("operator.toast.activated", "Platform Operator activated"),
              );
            } else {
              setCanaryWarning(outcome.canary.error ?? t("operator.canary.genericFailure", "The connection check did not succeed."));
              toast.warning(t("operator.toast.activatedButUnreachable", "Operator deployed, but it could not read your platform"));
            }
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
    <div className="flex h-[calc(100vh-8rem)] flex-col gap-4 py-4">
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

      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[1fr_20rem]">
        <OperatorChat
          messages={chat.messages}
          events={chat.events}
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
        />
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
