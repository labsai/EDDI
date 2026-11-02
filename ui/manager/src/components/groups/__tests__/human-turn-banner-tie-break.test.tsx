import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { HumanTurnBanner } from "@/components/groups/human-turn-banner";
import { tieBreakOptions, type PendingHumanInput } from "@/lib/api/groups";

const OPTIONS = ["Double down on SEO", "Launch the paid pilot"];

describe("HumanTurnBanner — tie-break variant (HUMAN_DECIDES)", () => {
  it("asks for one option and submits it with the optional rationale", () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <HumanTurnBanner displayName="Founder" renderedPrompt="The vote is tied." options={OPTIONS} onSubmit={onSubmit} />,
    );

    expect(screen.getByText(/you decide/)).toBeInTheDocument();
    const submit = screen.getByTestId("human-turn-submit");
    expect(submit).toBeDisabled();

    fireEvent.click(screen.getByTestId("human-turn-option-1"));
    expect(submit).not.toBeDisabled();
    fireEvent.change(screen.getByTestId("human-turn-input"), { target: { value: "  revenue first " } });
    fireEvent.click(submit);

    expect(onSubmit).toHaveBeenCalledWith("revenue first", "Launch the paid pilot");
  });

  it("does not need a rationale", () => {
    const onSubmit = vi.fn();
    renderWithProviders(<HumanTurnBanner displayName="Founder" renderedPrompt="" options={OPTIONS} onSubmit={onSubmit} />);

    fireEvent.click(screen.getByTestId("human-turn-option-0"));
    fireEvent.click(screen.getByTestId("human-turn-submit"));

    expect(onSubmit).toHaveBeenCalledWith("", "Double down on SEO");
  });

  it("tieBreakOptions reads options only from a TIE_BREAK detail", () => {
    const base = {
      memberId: "f",
      displayName: "F",
      phaseIdx: 0,
      repeatIdx: 0,
      speakerIdx: -1,
      entryType: "HUMAN_INPUT",
      renderedPrompt: "",
      onTimeout: "SKIP_TURN",
      requestedAt: "2026-11-02T10:00:00Z",
    } as PendingHumanInput;

    expect(tieBreakOptions({ ...base, detail: { kind: "TIE_BREAK", options: OPTIONS } })).toEqual(OPTIONS);
    expect(tieBreakOptions({ ...base, detail: { kind: "TASK", taskId: "t1" } })).toBeUndefined();
    expect(tieBreakOptions(base)).toBeUndefined();
    expect(tieBreakOptions(null)).toBeUndefined();
  });
});
