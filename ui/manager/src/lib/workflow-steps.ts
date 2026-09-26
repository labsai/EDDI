import type { WorkflowExtension } from "@/lib/api/workflows";

/** Workflow step types that resolve Qute placeholders in the step's output. */
const TEMPLATING_TYPES = new Set([
  "eddi://ai.labs.templating",
  "eddi://ai.labs.output.template",
]);

export function isTemplatingStep(type: string | undefined): boolean {
  return !!type && TEMPLATING_TYPES.has(type);
}

/**
 * Add a step to a workflow, keeping templating last.
 *
 * `eddi://ai.labs.templating` resolves the `{…}` placeholders that the steps
 * BEFORE it wrote to the output, so it has to run after all of them. "Add Task"
 * used to append, which put a new output or LLM step after templating: its
 * placeholders reached the user unresolved and nothing on screen said why. A new
 * non-templating step now goes in front of the trailing templating step(s); a
 * templating step itself still goes at the end.
 */
export function addWorkflowStep(
  steps: WorkflowExtension[],
  step: WorkflowExtension
): WorkflowExtension[] {
  if (isTemplatingStep(step.type)) return [...steps, step];
  let insertAt = steps.length;
  while (insertAt > 0 && isTemplatingStep(steps[insertAt - 1]!.type)) {
    insertAt--;
  }
  return [...steps.slice(0, insertAt), step, ...steps.slice(insertAt)];
}
