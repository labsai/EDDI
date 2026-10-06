import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { AlertTriangle, Bot, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { AlertDialog } from "@/components/ui/alert-dialog";
import {
  operatorKeys,
  useAdoptOperator,
  useOperatorAgentPresence,
  useRemoveOperatorAgent,
  useUnregisteredOperators,
} from "@/hooks/use-operator";
import { isGrantFailure, useDeployWithGrants } from "@/hooks/use-deploy-with-grants";
import { reportDeployOutcome } from "@/lib/deploy-outcome";
import { clearOperatorConfig, type OperatorConfig } from "@/lib/api/operator";
import type { DeploymentStatus } from "@/lib/api/agents";
import type { DiscoveredOperator } from "@/lib/api/operator-discovery";
import { getErrorMessage } from "@/lib/api-client";

const STATUS_VARIANT: Record<DeploymentStatus["status"], "success" | "warning" | "destructive" | "secondary"> = {
  READY: "success",
  IN_PROGRESS: "warning",
  ERROR: "destructive",
  NOT_FOUND: "secondary",
};

/**
 * Operator agents on this deployment that the operator config does not point
 * at — created through the setup API or MCP, imported, kept by an activation
 * whose vault grant was refused, or orphaned when the config variable went
 * missing. Each can be adopted (becomes THE operator), deployed through the
 * grant flow, or removed. Renders nothing while there are none.
 */
export function UnregisteredOperators({
  config,
  /** Whether the registered operator is present and usable; adopting is offered only when it is not. */
  registeredHealthy,
}: {
  config: OperatorConfig | null | undefined;
  registeredHealthy: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: candidates } = useUnregisteredOperators(config);
  const adopt = useAdoptOperator();
  const remove = useRemoveOperatorAgent();
  const { deploy, isRunning: deploying } = useDeployWithGrants();
  const [confirmRemove, setConfirmRemove] = useState<DiscoveredOperator | null>(null);
  const environment = config?.environment ?? "production";

  if (!candidates || candidates.length === 0) return null;
  const busy = adopt.isPending || remove.isPending || deploying;

  const handleDeploy = (candidate: DiscoveredOperator) => {
    const options = {
      agentId: candidate.agentId,
      version: candidate.version,
      environment,
      agentName: candidate.name,
      failure: isGrantFailure(candidate.failure) ? candidate.failure : undefined,
    };
    const refresh = () => void queryClient.invalidateQueries({ queryKey: operatorKeys.all });
    deploy(options)
      .then((outcome) => reportDeployOutcome(outcome, options, t, undefined, refresh))
      .catch((err) => toast.error(getErrorMessage(err)))
      .finally(refresh);
  };

  return (
    <section
      className="space-y-3 rounded-md border border-warning/40 bg-warning/5 p-4 text-sm"
      data-testid="operator-unregistered"
    >
      <div className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
        <div>
          <p className="font-medium text-foreground">
            {t("operator.unregistered.title", "Operator agents this screen does not manage")}
          </p>
          <p className="text-xs text-muted-foreground">
            {t(
              "operator.unregistered.description",
              "These agents look like a Platform Operator, but the operator configuration does not point at them. Adopt one to manage it here, or remove it.",
            )}
          </p>
        </div>
      </div>
      <ul className="space-y-2">
        {candidates.map((candidate) => (
          <li
            key={candidate.agentId}
            className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-card px-3 py-2"
            data-testid={`operator-unregistered-${candidate.agentId}`}
          >
            <Bot className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <p className="truncate font-medium text-foreground">{candidate.name || candidate.agentId}</p>
              <p className="truncate font-mono text-xs text-muted-foreground">
                {candidate.agentId} · v{candidate.version}
              </p>
              {candidate.failure?.message && (
                <p className="mt-1 text-xs text-destructive" data-testid={`operator-unregistered-failure-${candidate.agentId}`}>
                  {candidate.failure.message}
                </p>
              )}
            </div>
            <Badge variant={STATUS_VARIANT[candidate.status]} data-testid={`operator-unregistered-status-${candidate.agentId}`}>
              {t(`operator.unregistered.status.${candidate.status}`)}
            </Badge>
            <div className="flex flex-wrap gap-1.5">
              {!registeredHealthy && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() =>
                    adopt.mutate(
                      { candidate, environment },
                      {
                        onSuccess: () => toast.success(t("operator.unregistered.adopted", "Operator adopted — this screen now manages it.")),
                        onError: (err) => toast.error(getErrorMessage(err)),
                      },
                    )
                  }
                  data-testid={`operator-unregistered-adopt-${candidate.agentId}`}
                >
                  {adopt.isPending && <Loader2 className="me-2 h-3 w-3 animate-spin" />}
                  {t("operator.unregistered.adopt", "Adopt")}
                </Button>
              )}
              {candidate.status !== "READY" && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => handleDeploy(candidate)}
                  data-testid={`operator-unregistered-deploy-${candidate.agentId}`}
                >
                  {t("operator.unregistered.fixAndDeploy", "Fix grant and deploy")}
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={() => setConfirmRemove(candidate)}
                data-testid={`operator-unregistered-remove-${candidate.agentId}`}
              >
                {t("operator.unregistered.remove", "Remove")}
              </Button>
            </div>
          </li>
        ))}
      </ul>
      <AlertDialog
        open={confirmRemove !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmRemove(null);
        }}
        title={t("operator.unregistered.removeTitle", "Remove this operator agent?")}
        description={t(
          "operator.unregistered.removeBody",
          "The agent is undeployed, its conversations are ended, and it and the resources created for it are permanently deleted. This cannot be undone.",
        )}
        confirmLabel={t("operator.unregistered.remove", "Remove")}
        cancelLabel={t("common.cancel", "Cancel")}
        variant="destructive"
        onConfirm={() => {
          const candidate = confirmRemove;
          setConfirmRemove(null);
          if (!candidate) return;
          remove.mutate(
            { candidate, environment },
            {
              onSuccess: () => toast.success(t("operator.unregistered.removed", "Operator agent removed")),
              onError: (err) => toast.error(getErrorMessage(err)),
            },
          );
        }}
      />
    </section>
  );
}

/**
 * The inverse problem: the config points at an agent that no longer exists, or
 * that is switched on and not deployed. Says which, with the reason the backend
 * gave, and the one action that fixes it. Renders nothing when all is well.
 */
export function RegisteredOperatorHealth({
  config,
  status,
}: {
  config: OperatorConfig;
  status: { status: DeploymentStatus["status"]; failure?: DeploymentStatus["failure"] } | null | undefined;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const presence = useOperatorAgentPresence(config);
  const { deploy, isRunning } = useDeployWithGrants();
  const [clearing, setClearing] = useState(false);
  const refresh = () => void queryClient.invalidateQueries({ queryKey: operatorKeys.all });

  if (!config.agentId) return null;

  if (presence.data === "absent") {
    return (
      <div
        className="flex flex-wrap items-start gap-3 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
        role="alert"
        data-testid="operator-registered-missing"
      >
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
        <span className="flex-1">
          {t("operator.health.missing", {
            id: config.agentId,
            defaultValue: "The operator configuration points at agent {{id}}, which no longer exists.",
          })}
        </span>
        <Button
          size="sm"
          variant="outline"
          disabled={clearing}
          onClick={() => {
            setClearing(true);
            clearOperatorConfig()
              .then(() => toast.success(t("operator.health.cleared", "Operator configuration cleared")))
              .catch((err) => toast.error(getErrorMessage(err)))
              .finally(() => {
                setClearing(false);
                refresh();
              });
          }}
          data-testid="operator-registered-clear"
        >
          {t("operator.health.clear", "Clear configuration")}
        </Button>
      </div>
    );
  }

  const state = status?.status;
  if (!config.enabled || config.version == null || (state !== "ERROR" && state !== "NOT_FOUND")) return null;
  const failure = status?.failure;
  const handleDeploy = () => {
    const options = {
      agentId: config.agentId!,
      version: config.version!,
      environment: config.environment,
      failure: isGrantFailure(failure) ? failure : undefined,
    };
    deploy(options)
      .then((outcome) => reportDeployOutcome(outcome, options, t, undefined, refresh))
      .catch((err) => toast.error(getErrorMessage(err)))
      .finally(refresh);
  };
  return (
    <div
      className="flex flex-wrap items-start gap-3 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
      role="alert"
      data-testid="operator-registered-undeployed"
    >
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
      <span className="flex-1">
        <strong className="font-medium">
          {t("operator.health.notDeployed", "The operator is switched on, but its agent is not deployed.")}
        </strong>{" "}
        {failure?.message}
      </span>
      <Button size="sm" variant="outline" disabled={isRunning} onClick={handleDeploy} data-testid="operator-registered-deploy">
        {isRunning && <Loader2 className="me-2 h-3 w-3 animate-spin" />}
        {isGrantFailure(failure)
          ? t("operator.unregistered.fixAndDeploy", "Fix grant and deploy")
          : t("operator.health.deploy", "Deploy again")}
      </Button>
    </div>
  );
}
