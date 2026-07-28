import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
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
  seedConfig,
  type ActivationStage,
} from "@/hooks/use-operator";
import { useOperatorChat } from "@/hooks/use-operator-chat";
import { getErrorMessage } from "@/lib/api-client";
import type { OperatorConfig } from "@/lib/api/operator";

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

  const activate = useActivateOperator();
  const reactivate = useReactivateOperator();
  const deactivate = useDeactivateOperator();
  const reset = useResetOperator();
  const canary = useOperatorCanary();

  const status = useOperatorStatus(config);
  const chat = useOperatorChat(config);

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
            if (outcome.canary.ok) {
              setCanaryWarning(null);
              toast.success(t("operator.toast.activated"));
            } else {
              setCanaryWarning(outcome.canary.error ?? t("operator.canary.genericFailure"));
              toast.warning(t("operator.toast.activatedButUnreachable"));
            }
          },
          onError: (err) => {
            setStage("idle");
            setActivationError(getErrorMessage(err));
          },
        },
      );
    },
    [activate, t],
  );

  const handleReactivate = useCallback(() => {
    if (!config) return;
    reactivate.mutate(config, {
      onSuccess: () => toast.success(t("operator.toast.activated")),
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }, [config, reactivate, t]);

  const handleRecheck = useCallback(() => {
    if (!config) return;
    canary.mutate(config, {
      onSuccess: (result) => {
        if (result.ok) {
          setCanaryWarning(null);
          toast.success(t("operator.canary.passed"));
        } else {
          setCanaryWarning(result.error ?? t("operator.canary.genericFailure"));
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
        toast.success(t("operator.toast.deactivated"));
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
        toast.success(t("operator.toast.reset"));
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
        aria-label={t("common.loading")}
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
        <p className="font-medium">{t("operator.configError")}</p>
        <p className="text-sm text-muted-foreground">{getErrorMessage(configError)}</p>
        <Button onClick={() => refetch()} data-testid="operator-config-retry">
          {t("common.retry")}
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
        <p className="text-lg font-medium">{t("operator.paused.title")}</p>
        <p className="text-sm text-muted-foreground">{t("operator.paused.description")}</p>
        <div className="flex justify-center gap-2 pt-2">
          <Button
            onClick={handleReactivate}
            disabled={reactivate.isPending}
            data-testid="operator-reactivate"
          >
            {reactivate.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            {t("operator.paused.action")}
          </Button>
          <Button variant="outline" onClick={() => setShowActivation(true)}>
            {t("operator.status.reconfigure")}
          </Button>
          <Button
            variant="ghost"
            onClick={handleReset}
            disabled={reset.isPending}
            data-testid="operator-reset"
          >
            {t("operator.status.reset")}
          </Button>
        </div>
      </div>
    );
  }

  if (!isActive) {
    return (
      <div className="py-8">
        <EmptyState
          icon={Sparkles}
          title={t("operator.empty.title")}
          description={t("operator.empty.description")}
          actionLabel={t("operator.empty.action")}
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
          <h1 className="text-xl font-semibold">{t("operator.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("operator.subtitle")}</p>
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
            <strong className="font-medium">{t("operator.canary.title")}</strong>{" "}
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
              <Loader2 className="mr-2 h-3 w-3 animate-spin" />
            ) : (
              <RefreshCw className="mr-2 h-3 w-3" />
            )}
            {t("operator.canary.recheck")}
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
          onSend={chat.send}
          onStop={chat.stop}
          onReset={chat.reset}
        />
        <OperatorStatusPanel
          config={config!}
          status={status.data}
          statusLoading={status.isLoading}
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
