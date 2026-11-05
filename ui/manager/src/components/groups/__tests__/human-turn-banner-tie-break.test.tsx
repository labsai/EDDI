import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { HumanTurnBanner } from "@/components/groups/human-turn-banner";
import { pendingTurnTimeout, tieBreakOptions, type PendingHumanInput } from "@/lib/api/groups";

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

  it("a new pause starts over: the old choice and rationale are not carried to it", () => {
    const onSubmit = vi.fn();
    const { rerender } = renderWithProviders(
      <HumanTurnBanner pauseKey="gc-1:a" displayName="Founder" renderedPrompt="" options={OPTIONS} onSubmit={onSubmit} />,
    );
    fireEvent.click(screen.getByTestId("human-turn-option-1"));
    fireEvent.change(screen.getByTestId("human-turn-input"), { target: { value: "old reason" } });
    expect(screen.getByTestId("human-turn-submit")).not.toBeDisabled();

    rerender(<HumanTurnBanner pauseKey="gc-2:a" displayName="Founder" renderedPrompt="" options={OPTIONS} onSubmit={onSubmit} />);

    expect(screen.getByTestId("human-turn-submit")).toBeDisabled();
    expect((screen.getByTestId("human-turn-input") as HTMLTextAreaElement).value).toBe("");
  });

  it("the title does not claim a tie, since a failed quorum pauses the same way", () => {
    renderWithProviders(<HumanTurnBanner displayName="Founder" renderedPrompt="" options={OPTIONS} onSubmit={vi.fn()} />);
    expect(screen.getByRole("heading").textContent).not.toMatch(/tied/i);
  });

  it("a tie-break shows its own resolved timeout, any other human turn the member timeout", () => {
    const tie = { detail: { kind: "TIE_BREAK", options: OPTIONS } } as PendingHumanInput;
    const turn = {} as PendingHumanInput;
    expect(pendingTurnTimeout(tie, "PT10M", "PT4H")).toBe("PT10M");
    expect(pendingTurnTimeout(tie, undefined, "PT4H")).toBeNull();
    expect(pendingTurnTimeout(turn, "PT10M", "PT4H")).toBe("PT4H");
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
