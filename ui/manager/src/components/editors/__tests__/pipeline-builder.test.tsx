import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  PipelineBuilder,
  type PipelineItem,
} from "@/components/editors/pipeline-builder";

const emptyItems: PipelineItem[] = [];

const twoItems: PipelineItem[] = [
  {
    id: "step-0",
    index: 0,
    extension: {
      type: "eddi://ai.labs.rules",
      config: {
        uri: "eddi://ai.labs.rules/rulestore/rulesets/rule-123?version=1",
      },
    },
  },
  {
    id: "step-1",
    index: 1,
    extension: {
      type: "eddi://ai.labs.llm",
      config: {
        uri: "eddi://ai.labs.llm/llmstore/llms/llm-456?version=2",
      },
    },
  },
];

describe("PipelineBuilder", () => {
  const onChange = vi.fn();
  const onRemove = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows empty state when no items", () => {
    renderWithProviders(
      <PipelineBuilder
        items={emptyItems}
        onChange={onChange}
        onRemove={onRemove}
      />
    );
    expect(screen.getByTestId("pipeline-empty")).toBeInTheDocument();
    expect(
      screen.getByText("No tasks in this workflow")
    ).toBeInTheDocument();
  });

  it("shows pipeline list when items exist", () => {
    renderWithProviders(
      <PipelineBuilder
        items={twoItems}
        onChange={onChange}
        onRemove={onRemove}
      />
    );
    expect(screen.getByTestId("pipeline-list")).toBeInTheDocument();
  });

  it("renders pipeline items with data-testid", () => {
    renderWithProviders(
      <PipelineBuilder
        items={twoItems}
        onChange={onChange}
        onRemove={onRemove}
      />
    );
    expect(screen.getByTestId("pipeline-item-0")).toBeInTheDocument();
    expect(screen.getByTestId("pipeline-item-1")).toBeInTheDocument();
  });

  it("shows step numbers", () => {
    renderWithProviders(
      <PipelineBuilder
        items={twoItems}
        onChange={onChange}
        onRemove={onRemove}
      />
    );
    expect(screen.getByText("1")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });

  it("shows remove button for each item", () => {
    renderWithProviders(
      <PipelineBuilder
        items={twoItems}
        onChange={onChange}
        onRemove={onRemove}
      />
    );
    expect(screen.getByTestId("remove-ext-0")).toBeInTheDocument();
    expect(screen.getByTestId("remove-ext-1")).toBeInTheDocument();
  });

  it("calls onRemove when remove button clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PipelineBuilder
        items={twoItems}
        onChange={onChange}
        onRemove={onRemove}
      />
    );
    await user.click(screen.getByTestId("remove-ext-0"));
    expect(onRemove).toHaveBeenCalledWith(0);
  });

  it("shows version update button when resource is stale", () => {
    const latestVersions = { "rule-123": 3 };
    renderWithProviders(
      <PipelineBuilder
        items={twoItems}
        onChange={onChange}
        onRemove={onRemove}
        latestVersions={latestVersions}
        onUpdateVersion={vi.fn()}
      />
    );
    expect(screen.getByTestId("update-version-0")).toBeInTheDocument();
    expect(screen.getByText("v3")).toBeInTheDocument();
  });
});
