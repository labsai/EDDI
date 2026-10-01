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
 * non-templating step now goes in front of the LAST templating step (and any
 * templating steps directly before it); a templating step itself still goes at
 * the end. With no templating step at all, the new step is appended.
 *
 * Anchoring on the last templating step, not only a trailing one, covers a
 * workflow that is already misordered (`[templating, output]`): a trailing-only
 * scan appended there, after every templating step. It is the last one rather
 * than the first because a workflow may template mid-pipeline too
 * (`[parser, templating, rules, output, templating]`); going in front of the
 * first would move a new step ahead of the rules whose actions it reads.
 */
export function addWorkflowStep(
  steps: WorkflowExtension[],
  step: WorkflowExtension
): WorkflowExtension[] {
  if (isTemplatingStep(step.type)) return [...steps, step];
  let lastTemplating = -1;
  for (let i = steps.length - 1; i >= 0; i--) {
    if (isTemplatingStep(steps[i]!.type)) {
      lastTemplating = i;
      break;
    }
  }
  if (lastTemplating === -1) return [...steps, step];
  let insertAt = lastTemplating;
  while (insertAt > 0 && isTemplatingStep(steps[insertAt - 1]!.type)) {
    insertAt--;
  }
  return [...steps.slice(0, insertAt), step, ...steps.slice(insertAt)];
}
