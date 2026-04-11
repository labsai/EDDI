import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";

function renderDiff(source: string | null, target: string | null) {
  return render(
    <ResourceDiffViewer sourceContent={source} targetContent={target} />
  );
}

describe("ResourceDiffViewer", () => {
  it("renders nothing when both contents are null", () => {
    const { container } = renderDiff(null, null);
    expect(container.innerHTML).toBe("");
  });

  it("shows 'Content identical' when source equals target", () => {
    renderDiff('{"key": "value"}', '{"key": "value"}');
    expect(screen.getByText("Content identical")).toBeInTheDocument();
  });

  it("shows 'Content identical' even with different key order (deep-sort)", () => {
    renderDiff(
      '{"b": 1, "a": 2}',
      '{"a": 2, "b": 1}'
    );
    expect(screen.getByText("Content identical")).toBeInTheDocument();
  });

  it("shows 'Content identical' with nested key reorder", () => {
    renderDiff(
      '{"outer": {"z": 1, "a": 2}}',
      '{"outer": {"a": 2, "z": 1}}'
    );
    expect(screen.getByText("Content identical")).toBeInTheDocument();
  });

  it("shows diff lines when content differs", () => {
    renderDiff(
      '{"key": "new-value"}',
      '{"key": "old-value"}'
    );
    // Should show + and − markers
    expect(screen.getByText("+")).toBeInTheDocument();
    expect(screen.getByText("−")).toBeInTheDocument();
  });

  it("handles malformed JSON gracefully (raw text diff)", () => {
    renderDiff("not { json", "also not json");
    // Should still render a diff without crashing
    expect(screen.getByText("+")).toBeInTheDocument();
  });

  it("handles one side being null (entirely new content)", () => {
    renderDiff('{"new": true}', null);
    // All lines should be additions
    const additions = screen.getAllByText("+");
    expect(additions.length).toBeGreaterThan(0);
  });

  it("renders Target → Source header", () => {
    renderDiff('{"a": 1}', '{"a": 2}');
    expect(screen.getByText(/Target/)).toBeInTheDocument();
    expect(screen.getByText(/Source/)).toBeInTheDocument();
  });
});
