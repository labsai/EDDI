import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { PhaseHeader } from "@/components/groups/phase-header";

describe("PhaseHeader", () => {
  it("renders the phase name", () => {
    renderWithProviders(
      <PhaseHeader name="Initial Opinions" type="OPINION" entryCount={3}>
        <div>Child content</div>
      </PhaseHeader>
    );
    expect(screen.getByText("Initial Opinions")).toBeInTheDocument();
  });

  it("shows the correct icon for OPINION type", () => {
    renderWithProviders(
      <PhaseHeader name="Opinions" type="OPINION" entryCount={1}>
        <div>Content</div>
      </PhaseHeader>
    );
    expect(screen.getByText("💬")).toBeInTheDocument();
  });

  it("shows the correct icon for CRITIQUE type", () => {
    renderWithProviders(
      <PhaseHeader name="Review" type="CRITIQUE" entryCount={2}>
        <div>Content</div>
      </PhaseHeader>
    );
    expect(screen.getByText("🔍")).toBeInTheDocument();
  });

  it("shows the correct icon for SYNTHESIS type", () => {
    renderWithProviders(
      <PhaseHeader name="Summary" type="SYNTHESIS" entryCount={1}>
        <div>Content</div>
      </PhaseHeader>
    );
    expect(screen.getByText("⭐")).toBeInTheDocument();
  });

  it("shows the entry count badge", () => {
    renderWithProviders(
      <PhaseHeader name="Phase" type="OPINION" entryCount={5}>
        <div>Content</div>
      </PhaseHeader>
    );
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("shows children when expanded (default)", () => {
    renderWithProviders(
      <PhaseHeader name="Phase" type="OPINION" entryCount={1}>
        <div>Child content here</div>
      </PhaseHeader>
    );
    expect(screen.getByText("Child content here")).toBeInTheDocument();
  });

  it("hides children when collapsed", () => {
    renderWithProviders(
      <PhaseHeader
        name="Phase"
        type="OPINION"
        entryCount={1}
        defaultExpanded={false}
      >
        <div>Hidden content</div>
      </PhaseHeader>
    );
    expect(screen.queryByText("Hidden content")).not.toBeInTheDocument();
  });

  it("toggles children on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PhaseHeader name="Toggle Phase" type="OPINION" entryCount={1}>
        <div>Toggled content</div>
      </PhaseHeader>
    );

    // Initially visible
    expect(screen.getByText("Toggled content")).toBeInTheDocument();

    // Click to collapse
    await user.click(screen.getByText("Toggle Phase"));
    expect(screen.queryByText("Toggled content")).not.toBeInTheDocument();

    // Click to expand again
    await user.click(screen.getByText("Toggle Phase"));
    expect(screen.getByText("Toggled content")).toBeInTheDocument();
  });

  it("shows PARALLEL badge when turnOrder is PARALLEL", () => {
    renderWithProviders(
      <PhaseHeader
        name="Parallel Phase"
        type="OPINION"
        entryCount={2}
        turnOrder="PARALLEL"
      >
        <div>Content</div>
      </PhaseHeader>
    );
    expect(screen.getByText("Parallel")).toBeInTheDocument();
  });

  it("does not show PARALLEL badge when turnOrder is SEQUENTIAL", () => {
    renderWithProviders(
      <PhaseHeader
        name="Sequential Phase"
        type="OPINION"
        entryCount={2}
        turnOrder="SEQUENTIAL"
      >
        <div>Content</div>
      </PhaseHeader>
    );
    expect(screen.queryByText("Parallel")).not.toBeInTheDocument();
  });

  it("applies active ring when isActive", () => {
    const { container } = renderWithProviders(
      <PhaseHeader name="Active Phase" type="OPINION" entryCount={1} isActive>
        <div>Content</div>
      </PhaseHeader>
    );
    const phaseDiv = container.querySelector("[data-testid='phase-section-active-phase']");
    expect(phaseDiv?.className).toContain("ring-2");
  });

  it("applies special styling for SYNTHESIS type", () => {
    const { container } = renderWithProviders(
      <PhaseHeader name="Synthesis" type="SYNTHESIS" entryCount={1}>
        <div>Content</div>
      </PhaseHeader>
    );
    const phaseDiv = container.querySelector("[data-testid='phase-section-synthesis']");
    expect(phaseDiv?.className).toContain("border-primary/30");
  });

  it("has correct data-testid", () => {
    renderWithProviders(
      <PhaseHeader name="My Phase Name" type="OPINION" entryCount={1}>
        <div>Content</div>
      </PhaseHeader>
    );
    expect(
      screen.getByTestId("phase-section-my-phase-name")
    ).toBeInTheDocument();
  });
});
