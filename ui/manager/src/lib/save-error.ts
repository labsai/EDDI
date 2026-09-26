import type { TFunction } from "i18next";
import { getErrorMessage } from "@/lib/api-client";
import { CascadeReferenceError } from "@/lib/api/cascade-save";

/**
 * The message to show for a failed config save, translated where the failure is
 * one the cascade itself detected (`CascadeReferenceError`), and the usual
 * `getErrorMessage` text otherwise.
 *
 * Each key is spelled out rather than built from the code, so the i18n gate can
 * see every one of them.
 */
export function describeSaveError(err: unknown, t: TFunction): string {
  if (!(err instanceof CascadeReferenceError)) {
    return getErrorMessage(err);
  }
  const p = err.params;
  switch (err.code) {
    case "workflowChanged":
      return t(
        "cascadeSave.workflowChanged",
        "Workflow {{workflowId}} changed elsewhere (now version {{current}}, this page has version {{version}}). Reload the page. Nothing was saved.",
        p,
      );
    case "agentChanged":
      return t(
        "cascadeSave.agentChanged",
        "Agent {{agentId}} changed elsewhere (now version {{current}}, this page has version {{version}}). Reload the page. Nothing was saved.",
        p,
      );
    case "workflowMissingResource":
      return t(
        "cascadeSave.workflowMissingResource",
        "Workflow {{workflowId}} (version {{version}}) does not reference {{resource}}, so saving it here would not change the agent. Nothing was saved.",
        p,
      );
    case "agentWorkflowMismatch":
      return t(
        "cascadeSave.agentWorkflowMismatch",
        "Agent {{agentId}} (version {{version}}) references workflow {{workflowId}} at version {{found}}, not version {{expected}}. The agent changed since this page was opened. Reload it. Nothing was saved.",
        p,
      );
    case "agentMissingWorkflow":
      return t(
        "cascadeSave.agentMissingWorkflow",
        "Agent {{agentId}} (version {{version}}) does not reference workflow {{workflowId}}. Nothing was saved.",
        p,
      );
  }
}
