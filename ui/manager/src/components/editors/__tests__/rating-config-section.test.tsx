import { describe, expect, it, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { useState } from "react";
import { renderWithProviders } from "@/test/test-utils";
import type { Agent } from "@/lib/api/agents";
import { ratingConfigErrors } from "@/lib/rating-config";
import { RatingConfigSection } from "../rating-config-section";

function Harness({ initial, onValidity }: { initial: Agent; onValidity?: (invalid: boolean) => void }) {
  const [agent, setAgent] = useState<Agent>(initial);
  return (
    <>
      <RatingConfigSection agent={agent} onChange={setAgent} onValidityChange={onValidity} />
      <pre data-testid="draft">{JSON.stringify(agent.ratingConfig ?? null)}</pre>
    </>
  );
}

const draft = () => JSON.parse(screen.getByTestId("draft").textContent ?? "null");
const base: Agent = { workflows: [] } as unknown as Agent;

describe("ratingConfigErrors — mirrors RatingConfigValidator", () => {
  it("accepts an absent and a valid config", () => {
    expect(ratingConfigErrors(undefined)).toEqual({});
    expect(
      ratingConfigErrors({ enabled: true, ratingWindowHours: 0, comment: { maxLength: 4000, askBelowScore: 1 }, reasonTags: ["x".repeat(60)] }),
    ).toEqual({});
  });

  it.each([
    [{ ratingWindowHours: -1 }, "ratingWindowHours", "window"],
    [{ ratingWindowHours: 1.5 }, "ratingWindowHours", "window"],
    [{ conversationRating: { askAfterTurns: -2 } }, "askAfterTurns", "turns"],
    [{ conversationRating: { promptText: "p".repeat(501) } }, "promptText", "prompt"],
    [{ comment: { maxLength: 4001 } }, "maxLength", "maxLength"],
    [{ comment: { maxLength: 0 } }, "maxLength", "maxLength"],
    [{ comment: { askBelowScore: 1.2 } }, "askBelowScore", "askBelow"],
    [{ reasonTags: Array.from({ length: 21 }, (_, i) => `t${i}`) }, "reasonTags", "tagCount"],
    [{ reasonTags: ["y".repeat(61)] }, "reasonTags", "tagLength"],
    [{ reasonTags: ["a", "a"] }, "reasonTags", "tagDuplicate"],
  ])("refuses %j", (config, field, code) => {
    expect(ratingConfigErrors(config)).toMatchObject({ [field]: code });
  });
});

describe("RatingConfigSection", () => {
  it("is off by default and switches on into the draft", () => {
    renderWithProviders(<Harness initial={base} />);
    // Collapsed while off, like the other opt-in sections.
    expect(screen.queryByTestId("rating-config-section")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Ratings/ }));
    expect(screen.queryByTestId("rating-scale")).toBeNull();
    fireEvent.click(screen.getByTestId("rating-enabled"));
    expect(draft()).toEqual({ enabled: true });
    expect(screen.getByTestId("rating-scale")).toBeInTheDocument();
  });

  it("writes every field the backend knows", () => {
    renderWithProviders(<Harness initial={{ ...base, ratingConfig: { enabled: true } }} />);
    fireEvent.change(screen.getByTestId("rating-scale"), { target: { value: "FIVE_STAR" } });
    fireEvent.change(screen.getByTestId("rating-window"), { target: { value: "24" } });
    fireEvent.change(screen.getByTestId("rating-prompt"), { target: { value: "Did this help?" } });
    fireEvent.click(screen.getByTestId("rating-ask-on-end"));
    fireEvent.change(screen.getByTestId("rating-ask-after-turns"), { target: { value: "3" } });
    fireEvent.click(screen.getByTestId("rating-message-enabled"));
    fireEvent.click(screen.getByTestId("rating-comment-enabled"));
    fireEvent.change(screen.getByTestId("rating-comment-max"), { target: { value: "500" } });
    fireEvent.change(screen.getByTestId("rating-comment-ask-below"), { target: { value: "0.6" } });
    expect(draft()).toEqual({
      enabled: true,
      scale: "FIVE_STAR",
      ratingWindowHours: 24,
      conversationRating: { promptText: "Did this help?", askOnEnd: false, askAfterTurns: 3 },
      messageRating: { enabled: true },
      comment: { enabled: true, maxLength: 500, askBelowScore: 0.6 },
    });
  });

  it("an emptied number field falls back to the backend default", () => {
    renderWithProviders(<Harness initial={{ ...base, ratingConfig: { enabled: true, ratingWindowHours: 24 } }} />);
    fireEvent.change(screen.getByTestId("rating-window"), { target: { value: "" } });
    expect(draft()).toEqual({ enabled: true });
  });

  it("flags an out-of-range value and tells the page it cannot be saved", () => {
    const onValidity = vi.fn();
    renderWithProviders(<Harness initial={{ ...base, ratingConfig: { enabled: true, comment: { enabled: true } } }} onValidity={onValidity} />);
    fireEvent.change(screen.getByTestId("rating-comment-max"), { target: { value: "9000" } });
    expect(screen.getByTestId("rating-error-maxLength")).toBeInTheDocument();
    expect(screen.getByTestId("rating-comment-max")).toHaveAttribute("aria-invalid", "true");
    expect(onValidity).toHaveBeenLastCalledWith(true);

    fireEvent.change(screen.getByTestId("rating-comment-max"), { target: { value: "800" } });
    expect(screen.queryByTestId("rating-error-maxLength")).toBeNull();
    expect(onValidity).toHaveBeenLastCalledWith(false);
  });

  it("keeps an invalid field visible after its switch is turned off, so it can still be fixed", () => {
    renderWithProviders(<Harness initial={{ ...base, ratingConfig: { enabled: true, comment: { enabled: true } } }} />);
    fireEvent.change(screen.getByTestId("rating-comment-max"), { target: { value: "9000" } });
    fireEvent.click(screen.getByTestId("rating-comment-enabled"));
    expect(screen.getByTestId("rating-comment-max")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("rating-enabled"));
    expect(screen.getByTestId("rating-comment-max")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("rating-comment-max"), { target: { value: "800" } });
    // Valid again: switched-off settings fold away as before.
    expect(screen.queryByTestId("rating-comment-max")).toBeNull();
    expect(screen.queryByTestId("rating-scale")).toBeNull();
  });

  it("flags a duplicated reason", () => {
    renderWithProviders(<Harness initial={{ ...base, ratingConfig: { enabled: true, reasonTags: ["Too slow", "Too slow"] } }} />);
    expect(screen.getByTestId("rating-error-reasonTags")).toBeInTheDocument();
  });
});
