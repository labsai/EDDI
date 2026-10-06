import type { TFunction } from "i18next";
import { toast } from "sonner";
import type { DeploymentFailure } from "@/lib/api/agents";
import {
  isGrantFailure,
  runDeployWithGrants,
  type DeployWithGrantsOptions,
  type DeployWithGrantsOutcome,
} from "@/hooks/use-deploy-with-grants";

/**
 * The text for a deployment that did not end READY: the backend's reason when
 * it gave one, otherwise the generic "Deployment failed" (or "timed out" for a
 * deploy that never left IN_PROGRESS).
 */
export function deployFailureMessage(
  outcome: Extract<DeployWithGrantsOutcome, { kind: "failed" }>,
  t: TFunction,
): string {
  if (outcome.message) return outcome.message;
  if (outcome.result.status === "IN_PROGRESS") return t("chatDrawer.timeout", "Deploy timed out");
  return t("editor.deployFailed", "Deployment failed");
}

/**
 * Re-open the grant dialog for a refused deployment and deploy again — what a
 * "Fix" button next to a VAULT_GRANT_MISSING failure does. Reports its own
 * outcome the same way.
 */
export async function fixGrantAndRedeploy(
  options: DeployWithGrantsOptions & { failure: DeploymentFailure },
  t: TFunction,
  onSettled?: () => void,
): Promise<DeployWithGrantsOutcome> {
  try {
    const outcome = await runDeployWithGrants(options);
    reportDeployOutcome(outcome, options, t, undefined, onSettled);
    return outcome;
  } finally {
    onSettled?.();
  }
}

/**
 * Toast the outcome of a grant-aware deploy, for the flows that have no other
 * place to show it (the agent card, the "Deploy" action of a save toast). A
 * grant refusal gets a **Fix** action that reopens the grant dialog.
 */
export function reportDeployOutcome(
  outcome: DeployWithGrantsOutcome,
  options: DeployWithGrantsOptions,
  t: TFunction,
  successMessage?: string,
  /** Run after a Fix-triggered redeploy ends — the caller's cache refresh. */
  onSettled?: () => void,
): void {
  if (outcome.kind === "deployed") {
    toast.success(successMessage ?? t("agents.deploySuccess", "Agent deployed successfully"));
    return;
  }
  if (outcome.kind === "cancelled") {
    toast.info(t("grantRequired.cancelled", "Not deployed — the vault key was not granted."));
    return;
  }
  const message = deployFailureMessage(outcome, t);
  const failure = outcome.failure;
  toast.error(message, {
    action: isGrantFailure(failure)
      ? {
          label: t("grantRequired.fix", "Fix"),
          onClick: () => void fixGrantAndRedeploy({ ...options, failure }, t, onSettled),
        }
      : undefined,
  });
}
