import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatCard } from "../stat-card";

function MockIcon({ className }: { className?: string }) {
  return <svg data-testid="mock-icon" className={className} />;
}

describe("StatCard", () => {
  it("renders label and value", () => {
    render(<StatCard label="Total" value={42} icon={MockIcon} />);
    expect(screen.getByText("Total")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByTestId("mock-icon")).toBeInTheDocument();
  });

  it("renders subtitle when provided", () => {
    render(
      <StatCard label="Cost" value="$1.23" subtitle="Last 30 days" icon={MockIcon} />,
    );
    expect(screen.getByText("Last 30 days")).toBeInTheDocument();
  });

  it("omits subtitle when not provided", () => {
    const { container } = render(
      <StatCard label="Items" value={7} icon={MockIcon} />,
    );
    expect(container.querySelectorAll(".text-sm.text-muted-foreground")).toHaveLength(0);
  });
});
