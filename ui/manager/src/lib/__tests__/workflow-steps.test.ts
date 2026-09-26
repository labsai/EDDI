import { describe, expect, it } from "vitest";
import { addWorkflowStep } from "@/lib/workflow-steps";
import type { WorkflowExtension } from "@/lib/api/workflows";

const step = (type: string): WorkflowExtension => ({
  type,
  extensions: {},
  config: {},
});
const types = (steps: WorkflowExtension[]) => steps.map((s) => s.type);

describe("addWorkflowStep", () => {
  // Appending put a new output/LLM step AFTER templating, so its {…}
  // placeholders reached the user unresolved.
  it("inserts a new step in front of a trailing templating step", () => {
    const steps = [
      step("eddi://ai.labs.parser"),
      step("eddi://ai.labs.output"),
      step("eddi://ai.labs.templating"),
    ];
    expect(types(addWorkflowStep(steps, step("eddi://ai.labs.llm")))).toEqual([
      "eddi://ai.labs.parser",
      "eddi://ai.labs.output",
      "eddi://ai.labs.llm",
      "eddi://ai.labs.templating",
    ]);
  });

  it("keeps a whole trailing block of templating steps last", () => {
    const steps = [
      step("eddi://ai.labs.output"),
      step("eddi://ai.labs.output.template"),
      step("eddi://ai.labs.templating"),
    ];
    expect(types(addWorkflowStep(steps, step("eddi://ai.labs.property")))).toEqual([
      "eddi://ai.labs.output",
      "eddi://ai.labs.property",
      "eddi://ai.labs.output.template",
      "eddi://ai.labs.templating",
    ]);
  });

  it("appends when there is no templating step, or when adding templating itself", () => {
    const steps = [step("eddi://ai.labs.parser")];
    expect(types(addWorkflowStep(steps, step("eddi://ai.labs.output")))).toEqual([
      "eddi://ai.labs.parser",
      "eddi://ai.labs.output",
    ]);
    expect(
      types(addWorkflowStep(steps, step("eddi://ai.labs.templating")))
    ).toEqual(["eddi://ai.labs.parser", "eddi://ai.labs.templating"]);
  });

  it("does not mutate the input", () => {
    const steps = [step("eddi://ai.labs.templating")];
    addWorkflowStep(steps, step("eddi://ai.labs.output"));
    expect(steps).toHaveLength(1);
  });
});
