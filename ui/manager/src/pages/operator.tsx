import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Sparkles, AlertTriangle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/shared/empty-state";
import { OperatorActivation } from "@/components/operator/operator-activation";
import { OperatorChat } from "@/components/operator/operator-chat";
import { OperatorStatusPanel } from "@/components/operator/operator-status";
import {
  useOperatorConfig,
  useOperatorStatus,
  useActivateOperator,
  useDeactivateOperator,
  useResetOperator,
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

  const activate = useActivateOperator();
  const deactivate = useDeactivateOperator();
  const reset = useResetOperator();

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
          onSuccess: () => {
            setStage("idle");
            setShowActivation(false);
            toast.success(t("operator.toast.activated"));
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

  const handleDeactivate = useCallback(() => {
    if (!config) return;
    deactivate.mutate(config, {
      onSuccess: () => {
        chat.reset();
        toast.success(t("operator.toast.deactivated"));
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }, [config, deactivate, chat, t]);

  const handleReset = useCallback(() => {
    if (!config) return;
    reset.mutate(config, {
      onSuccess: () => {
        chat.reset();
        setShowActivation(false);
        toast.success(t("operator.toast.reset"));
      },
      onError: (err) => toast.error(getErrorMessage(err)),
    });
  }, [config, reset, chat, t]);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-24 text-muted-foreground">
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

  if (showActivation || !isActive) {
    if (!showActivation && !isActive) {
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
      <div className="py-8">
        <OperatorActivation
          initial={seedConfig(config)}
          stage={stage}
          error={activationError}
          onActivate={handleActivate}
          onCancel={isActive ? () => setShowActivation(false) : undefined}
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

      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[1fr_20rem]">
        <OperatorChat
          messages={chat.messages}
          events={chat.events}
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
          busy={deactivate.isPending || reset.isPending}
        />
      </div>
    </div>
  );
}
