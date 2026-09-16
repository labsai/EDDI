import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { HumanTurnBanner } from "@/components/groups/human-turn-banner";

describe("HumanTurnBanner", () => {
  it("renders the rendered prompt and the pending member's name", () => {
    renderWithProviders(
      <HumanTurnBanner
        displayName="Alex"
        renderedPrompt="Cast your vote: Option A or Option B?"
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByText(/Alex/)).toBeInTheDocument();
    expect(screen.getByTestId("human-turn-prompt")).toHaveTextContent(
      "Cast your vote: Option A or Option B?",
    );
  });

  it("submits the trimmed response and nothing else", () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <HumanTurnBanner displayName="Alex" renderedPrompt="" onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByTestId("human-turn-input"), {
      target: { value: "  I vote for Option A  " },
    });
    fireEvent.click(screen.getByTestId("human-turn-submit"));

    expect(onSubmit).toHaveBeenCalledWith("I vote for Option A");
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("disables submit while the response is blank or whitespace-only", () => {
    renderWithProviders(
      <HumanTurnBanner displayName="Alex" renderedPrompt="" onSubmit={vi.fn()} />,
    );

    const submit = screen.getByTestId("human-turn-submit");
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByTestId("human-turn-input"), { target: { value: "   " } });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByTestId("human-turn-input"), { target: { value: "ok" } });
    expect(submit).not.toBeDisabled();
  });

  it("never submits while isSubmitting is true, even on Mod+Enter", () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <HumanTurnBanner
        displayName="Alex"
        renderedPrompt=""
        onSubmit={onSubmit}
        isSubmitting
      />,
    );

    const input = screen.getByTestId("human-turn-input");
    expect(input).toBeDisabled();
    expect(screen.getByTestId("human-turn-submit")).toBeDisabled();

    fireEvent.click(screen.getByTestId("human-turn-submit"));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("shows an overdue badge once the turn timeout has elapsed", () => {
    const pastRequestedAt = new Date(Date.now() - 60_000).toISOString(); // 1 minute ago
    renderWithProviders(
      <HumanTurnBanner
        displayName="Alex"
        renderedPrompt=""
        requestedAt={pastRequestedAt}
        turnTimeout="PT30S" // 30s timeout, already elapsed
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByText(/Overdue/)).toBeInTheDocument();
  });

  it("does not render a countdown when no turnTimeout is configured", () => {
    renderWithProviders(
      <HumanTurnBanner
        displayName="Alex"
        renderedPrompt=""
        requestedAt={new Date().toISOString()}
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.queryByText(/Overdue/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Remaining/)).not.toBeInTheDocument();
  });
});
