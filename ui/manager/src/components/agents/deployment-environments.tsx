import { useTranslation } from "react-i18next";
import { CheckCircle2, Loader2, XCircle, MinusCircle } from "lucide-react";
import { type Environment } from "@/lib/constants";
import type { EnvironmentStatus } from "@/lib/api/agents";
import { deployedEnvironments, isAnyEnvironmentBusy } from "@/lib/deployment-environments";
import { useEnvironmentLabel } from "@/hooks/use-environment-label";
import { cn } from "@/lib/utils";

/**
 * The agent card's deployment badge. Pure helpers live in
 * `@/lib/deployment-environments`.
 */

/**
 * Per-environment styling. `production` is deliberately the stronger colour: on
 * a list of agents the one fact worth spotting instantly is whether something is
 * live for real users, and a test deployment should not look identical to it.
 */
const ENV_CHIP_CLASSES: Record<Environment, string> = {
  production: "bg-emerald-500/15 text-emerald-700 ring-emerald-500/30 dark:text-emerald-400",
  test: "bg-sky-500/15 text-sky-700 ring-sky-500/30 dark:text-sky-400",
};

/**
 * The card's deployment badge: one pill per environment the agent is live in, or
 * a single "Not deployed" pill.
 *
 * Naming the environment rather than showing a bare "Deployed" is the whole
 * point — "Deployed" alone is what sent someone hunting for an agent that was
 * running fine in `test`.
 */
export function DeploymentEnvironmentBadge({
  statuses,
  isLoading,
  className,
  "data-testid": testId,
}: {
  statuses: EnvironmentStatus[] | undefined;
  isLoading?: boolean;
  className?: string;
  "data-testid"?: string;
}) {
  const { t } = useTranslation();
  const label = useEnvironmentLabel();
  const live = deployedEnvironments(statuses);
  const busy = isAnyEnvironmentBusy(statuses);
  const errored = (statuses ?? []).filter((s) => s.status === "ERROR");

  const pill = "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ring-1";

  if (isLoading) {
    return (
      <span className={cn(pill, "bg-muted text-muted-foreground ring-border", className)} data-testid={testId}>
        <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
        {t("status.checking", "Checking…")}
      </span>
    );
  }

  if (live.length === 0) {
    // An in-flight deploy with nothing live yet is its own state — reporting
    // "Not deployed" while a deploy runs reads as a failure.
    if (busy) {
      return (
        <span className={cn(pill, "bg-amber-500/15 text-amber-700 ring-amber-500/30 dark:text-amber-400", className)} data-testid={testId}>
          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
          {t("status.deploying", "Deploying...")}
        </span>
      );
    }
    if (errored.length > 0) {
      return (
        <span className={cn(pill, "bg-destructive/15 text-destructive ring-destructive/30", className)} data-testid={testId}>
          <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
          {t("status.error", "Error")}
        </span>
      );
    }
    return (
      <span className={cn(pill, "bg-muted text-muted-foreground ring-border", className)} data-testid={testId}>
        <MinusCircle className="h-3.5 w-3.5" aria-hidden="true" />
        {t("status.notDeployed", "Not deployed")}
      </span>
    );
  }

  return (
    <span className={cn("inline-flex flex-wrap items-center gap-1", className)} data-testid={testId}>
      {live.map((env) => (
        <span
          key={env}
          className={cn(pill, ENV_CHIP_CLASSES[env])}
          data-testid={`env-chip-${env}`}
          title={t("agents.liveIn", "Live in {{environment}}", { environment: label(env) })}
        >
          <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
          {label(env)}
        </span>
      ))}
      {/* An environment that FAILED must still be visible when another one is
          live — showing only the healthy chip would hide a broken production
          deploy behind a green test one, which is the same class of omission
          this component exists to fix. */}
      {errored.map(({ environment }) => (
        <span
          key={environment}
          className={cn(pill, "bg-destructive/15 text-destructive ring-destructive/30")}
          data-testid={`env-chip-error-${environment}`}
          title={t("agents.deployFailedIn", "Deployment failed in {{environment}}", {
            environment: label(environment),
          })}
        >
          <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
          {label(environment)}
        </span>
      ))}
      {busy && (
        <Loader2
          className="h-3.5 w-3.5 animate-spin text-muted-foreground"
          aria-label={t("status.deploying", "Deploying...")}
        />
      )}
    </span>
  );
}
