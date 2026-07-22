import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { AdvisorAvatar } from "../advisor-avatar";

describe("AdvisorAvatar", () => {
  it("renders initials from name", () => {
    render(<AdvisorAvatar name="Test Agent" agentId="agent-1" />);
    expect(screen.getByText("TA")).toBeInTheDocument();
  });

  it("renders single initial for one-word name", () => {
    render(<AdvisorAvatar name="Bot" agentId="agent-2" />);
    expect(screen.getByText("B")).toBeInTheDocument();
  });

  it("applies size classes", () => {
    render(<AdvisorAvatar name="Big Bot" agentId="agent-3" size="xl" />);
    const avatar = screen.getByRole("img");
    expect(avatar).toBeInTheDocument();
  });

  it("shows role badge when showRole is true", () => {
    render(
      <AdvisorAvatar name="Expert" agentId="agent-4" role="Analyst" showRole />,
    );
    expect(screen.getByText("Analyst")).toBeInTheDocument();
  });
});
