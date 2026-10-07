import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { PipelineBuilder, type PipelineItem } from "@/components/editors/pipeline-builder";
import {
  buildStepResourceLink,
  isStepInSavedWorkflow,
  parseExtensionUri,
} from "@/lib/workflow-step-links";

const RULES_URI = "eddi://ai.labs.rules/rulestore/rulesets/rule-123?version=2";
const LLM_URI = "eddi://ai.labs.llm/llmstore/llms/llm-456?version=5";

const items: PipelineItem[] = [
  { id: "s0", index: 0, extension: { type: "eddi://ai.labs.rules", config: { uri: RULES_URI }, extensions: {} } },
  { id: "s1", index: 1, extension: { type: "eddi://ai.labs.llm", config: { uri: LLM_URI }, extensions: {} } },
];

function hrefOf(index: number): string {
  const item = screen.getByTestId(`pipeline-item-${index}`);
  return item.querySelector("a[href]")!.getAttribute("href")!;
}

describe("PipelineBuilder — edit links", () => {
  it("opens the resource at the version the step references, with the cascade context", () => {
    renderWithProviders(
      <PipelineBuilder
        items={items}
        onChange={vi.fn()}
        onRemove={vi.fn()}
        workflowId="wf1"
        workflowVersion={4}
        agentId="agent1"
        agentVer="7"
      />,
    );
    const url = new URL(hrefOf(0), "http://x");
    expect(url.pathname).toBe("/manage/resources/rules/rule-123");
    expect(url.searchParams.get("version")).toBe("2");
    expect(url.searchParams.get("wfId")).toBe("wf1");
    expect(url.searchParams.get("wfVer")).toBe("4");
    expect(url.searchParams.get("agentVer")).toBe("7");
    expect(new URL(hrefOf(1), "http://x").searchParams.get("version")).toBe("5");
  });

  it("offers Save & edit, not an Edit link, for a step the saved workflow lacks", async () => {
    const onSaveAndEdit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <PipelineBuilder
        items={items}
        onChange={vi.fn()}
        onRemove={vi.fn()}
        workflowId="wf1"
        workflowVersion={4}
        // only the rules step is in the saved workflow
        savedStepUris={[RULES_URI]}
        onSaveAndEdit={onSaveAndEdit}
      />,
    );
    // The saved step keeps its link.
    expect(screen.getByTestId("pipeline-item-0").querySelector("a[href]")).not.toBeNull();
    expect(screen.queryByTestId("unsaved-step-0")).not.toBeInTheDocument();

    // The new one leaves the page nowhere: no link, a labelled action instead.
    const unsaved = screen.getByTestId("pipeline-item-1");
    expect(unsaved.querySelector("a[href]")).toBeNull();
    expect(screen.getByTestId("unsaved-step-1")).toBeInTheDocument();
    await user.click(screen.getByTestId("save-and-edit-1"));
    expect(onSaveAndEdit).toHaveBeenCalledWith(1);
  });

  it("treats a step as saved when the workflow holds it at another version", () => {
    renderWithProviders(
      <PipelineBuilder
        items={items}
        onChange={vi.fn()}
        onRemove={vi.fn()}
        savedStepUris={[RULES_URI.replace("version=2", "version=1"), LLM_URI]}
      />,
    );
    expect(screen.queryByTestId("unsaved-step-0")).not.toBeInTheDocument();
  });

  it("labels the remove button for assistive technology", () => {
    renderWithProviders(<PipelineBuilder items={items} onChange={vi.fn()} onRemove={vi.fn()} />);
    expect(screen.getAllByRole("button", { name: "Remove task" })).toHaveLength(2);
  });
});

describe("workflow-step-links", () => {
  it("parses store paths into resource slugs", () => {
    expect(parseExtensionUri(RULES_URI)).toEqual({ slug: "rules", id: "rule-123" });
    expect(parseExtensionUri("not a uri")).toBeNull();
  });

  it("builds no link for something that is not a resource uri", () => {
    expect(buildStepResourceLink("nope", {})).toBeNull();
  });

  it("matches saved steps by store and id, ignoring the version", () => {
    expect(isStepInSavedWorkflow(LLM_URI, [RULES_URI])).toBe(false);
    expect(isStepInSavedWorkflow(LLM_URI, [LLM_URI.replace("version=5", "version=1")])).toBe(true);
  });
});
