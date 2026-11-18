import { blockingRegexProblem } from "@/components/editors/llm/guardrails";
import type { TFunction } from "i18next";
import type { GuardrailConfig } from "@eddi/app-kit/components/editors/llm/types";

/**
 * Blocking checks on a whole configuration document, run by the editor layout on
 * the parsed JSON whichever tab is showing. A form editor reports the same
 * problems while it is mounted (`useReportInvalid`); on the JSON tab it is not,
 * and without this an invalid regex or temperature could be saved by switching tabs.
 *
 * A validator returns the reason Save is blocked, or null.
 */
export type DocumentValidator = (document: unknown, t: TFunction) => string | null;

/** Whether `value` is a number a provider can take as a sampling temperature (empty = provider default). */
export function isTemperatureValue(value: string): boolean {
  return value.trim() === "" || (Number.isFinite(Number(value)) && Number(value) >= 0);
}

interface LlmDocument {
  tasks?: {
    parameters?: Record<string, unknown>;
    inputGuardrails?: GuardrailConfig[];
    outputGuardrails?: GuardrailConfig[];
  }[];
}

/** The LLM configuration: temperature must be a number of 0 or more; regex-filter lines must compile. */
export const validateLlmDocument: DocumentValidator = (document, t) => {
  const tasks = (document as LlmDocument | null)?.tasks;
  if (!Array.isArray(tasks)) return null;
  for (const task of tasks) {
    const temperature = task?.parameters?.temperature;
    if (temperature !== undefined && temperature !== null && !isTemperatureValue(String(temperature))) {
      return t("llmEditor.temperatureBlocksSave", "The temperature must be a number, 0 or more — fix it before saving.");
    }
    for (const g of [...(task?.inputGuardrails ?? []), ...(task?.outputGuardrails ?? [])]) {
      const bad = blockingRegexProblem(g);
      if (bad) {
        return t("llmEditor.grPatternBlocksSave", "A regex-filter guardrail has an invalid pattern (line {{line}}) — fix it before saving.", {
          line: bad.line,
        });
      }
    }
  }
  return null;
};

/** Validators by resource slug (`RESOURCE_TYPES`). */
export const DOCUMENT_VALIDATORS: Record<string, DocumentValidator | undefined> = {
  llm: validateLlmDocument,
};
