import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ActionBadge } from "@/components/shared/action-badge";

describe("ActionBadge", () => {
  it("renders CREATE action with 'New' label", () => {
    renderWithProviders(<ActionBadge action="CREATE" />);
    expect(screen.getByText("New")).toBeInTheDocument();
  });

  it("renders CREATE with emerald color", () => {
    const { container } = renderWithProviders(
      <ActionBadge action="CREATE" />
    );
    const span = container.querySelector(".text-emerald-500");
    expect(span).not.toBeNull();
  });

  it("renders UPDATE action with 'Update' label", () => {
    renderWithProviders(<ActionBadge action="UPDATE" />);
    expect(screen.getByText("Update")).toBeInTheDocument();
  });

  it("renders UPDATE with blue color", () => {
    const { container } = renderWithProviders(
      <ActionBadge action="UPDATE" />
    );
    const span = container.querySelector(".text-blue-500");
    expect(span).not.toBeNull();
  });

  it("renders SKIP action with 'Up to date' label", () => {
    renderWithProviders(<ActionBadge action="SKIP" />);
    expect(screen.getByText("Up to date")).toBeInTheDocument();
  });

  it("renders SKIP with muted color", () => {
    const { container } = renderWithProviders(
      <ActionBadge action="SKIP" />
    );
    const span = container.querySelector(".text-muted-foreground");
    expect(span).not.toBeNull();
  });

  it("renders CONFLICT action with 'Conflict' label", () => {
    renderWithProviders(<ActionBadge action="CONFLICT" />);
    expect(screen.getByText("Conflict")).toBeInTheDocument();
  });

  it("renders CONFLICT with destructive color", () => {
    const { container } = renderWithProviders(
      <ActionBadge action="CONFLICT" />
    );
    const span = container.querySelector(".text-destructive");
    expect(span).not.toBeNull();
  });

  it("renders unknown action as plain text", () => {
    renderWithProviders(<ActionBadge action="UNKNOWN" />);
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
  });

  it("renders unknown action with muted style", () => {
    const { container } = renderWithProviders(
      <ActionBadge action="SOME_OTHER" />
    );
    const span = container.querySelector(".text-muted-foreground");
    expect(span).not.toBeNull();
    expect(span?.textContent).toBe("SOME_OTHER");
  });
});
