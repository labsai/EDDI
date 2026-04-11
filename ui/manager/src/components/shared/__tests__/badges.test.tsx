import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ActionBadge } from "@/components/shared/action-badge";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";

describe("ActionBadge", () => {
  it("renders 'New' for CREATE action", () => {
    render(<ActionBadge action="CREATE" />);
    expect(screen.getByText("New")).toBeInTheDocument();
  });

  it("renders 'Update' for UPDATE action", () => {
    render(<ActionBadge action="UPDATE" />);
    expect(screen.getByText("Update")).toBeInTheDocument();
  });

  it("renders 'Up to date' for SKIP action", () => {
    render(<ActionBadge action="SKIP" />);
    expect(screen.getByText("Up to date")).toBeInTheDocument();
  });

  it("renders 'Conflict' for CONFLICT action", () => {
    render(<ActionBadge action="CONFLICT" />);
    expect(screen.getByText("Conflict")).toBeInTheDocument();
  });

  it("renders unknown action as text", () => {
    render(<ActionBadge action="CUSTOM" />);
    expect(screen.getByText("CUSTOM")).toBeInTheDocument();
  });
});

describe("ResourceTypeBadge", () => {
  it.each([
    ["agent", "purple"],
    ["workflow", "blue"],
    ["behavior", "amber"],
    ["rules", "amber"],
    ["httpcalls", "green"],
    ["apicalls", "green"],
    ["langchain", "pink"],
    ["llm", "pink"],
    ["output", "cyan"],
    ["propertysetter", "orange"],
    ["dictionary", "teal"],
    ["mcpcalls", "indigo"],
    ["rag", "violet"],
    ["snippets", "rose"],
  ])("renders '%s' with correct color class containing '%s'", (type, color) => {
    const { container } = render(<ResourceTypeBadge type={type} />);
    const badge = container.querySelector("span")!;
    expect(badge.textContent).toBe(type);
    expect(badge.className).toContain(color);
  });

  it("renders unknown type with secondary fallback styling", () => {
    const { container } = render(<ResourceTypeBadge type="unknown" />);
    const badge = container.querySelector("span")!;
    expect(badge.textContent).toBe("unknown");
    expect(badge.className).toContain("bg-secondary");
  });

  it("strips .json extension from type", () => {
    render(<ResourceTypeBadge type="behavior.json" />);
    expect(screen.getByText("behavior")).toBeInTheDocument();
  });
});
