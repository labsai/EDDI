import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { RatingCard } from "./RatingCard";
import { parseRatingProfile, type RatingProfile } from "@/api/rating-api";

const stars = (extra: Record<string, unknown> = {}): RatingProfile =>
  parseRatingProfile({ scale: "FIVE_STAR", reasonTags: ["Helpful", "Too slow"], comment: true, ...extra })!;

function renderCard(profile: RatingProfile, overrides: Partial<Parameters<typeof RatingCard>[0]> = {}) {
  const props = {
    profile,
    current: null,
    onSubmit: vi.fn().mockResolvedValue(true),
    onWithdraw: vi.fn().mockResolvedValue(true),
    onClose: vi.fn(),
    ...overrides,
  };
  render(<RatingCard {...props} />);
  return props;
}

describe("RatingCard", () => {
  it("five stars are a radio group; one tap rates, then reasons and a comment appear", async () => {
    const props = renderCard(stars());
    expect(screen.getByRole("radiogroup", { name: "How did we do?" })).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(5);
    expect(screen.queryByTestId("rating-card-details")).toBeNull();
    expect(screen.getByText(/shared with the team running this assistant/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("radio", { name: "2 of 5 stars" }));
    expect(props.onSubmit).toHaveBeenCalledWith({ score: 2, tags: [], comment: "" });

    await screen.findByTestId("rating-card-details");
    expect(screen.getByRole("radio", { name: "2 of 5 stars" })).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByRole("button", { name: "Too slow" }));
    fireEvent.change(screen.getByTestId("rating-card-comment"), { target: { value: "kept looping" } });
    fireEvent.click(screen.getByTestId("rating-card-send"));

    expect(props.onSubmit).toHaveBeenLastCalledWith({ score: 2, tags: ["Too slow"], comment: "kept looping" });
    expect(await screen.findByTestId("rating-card-thanks")).toHaveTextContent(/goes to the team running this assistant/);
  });

  it("hides the comment box above askBelowScore", async () => {
    renderCard(stars({ reasonTags: [], commentAskBelowScore: 0.6 }));
    fireEvent.click(screen.getByRole("radio", { name: "5 of 5 stars" }));
    // No reasons, no comment for a happy score: straight to thanks.
    expect(await screen.findByTestId("rating-card-thanks")).toBeInTheDocument();
  });

  it("offers the comment for a low score under askBelowScore", async () => {
    renderCard(stars({ reasonTags: [], commentAskBelowScore: 0.6 }));
    fireEvent.click(screen.getByRole("radio", { name: "1 of 5 stars" }));
    expect(await screen.findByTestId("rating-card-comment")).toBeInTheDocument();
  });

  it("thumbs scale uses pressed buttons", async () => {
    const props = renderCard(parseRatingProfile({ scale: "THUMBS" })!);
    expect(screen.queryByRole("radiogroup")).toBeNull();
    fireEvent.click(screen.getByTestId("rating-thumb-up"));
    expect(props.onSubmit).toHaveBeenCalledWith({ score: 1, tags: [], comment: "" });
    await screen.findByTestId("rating-card-thanks");
  });

  it("stays on the score when the server refuses it", async () => {
    renderCard(stars(), { onSubmit: vi.fn().mockResolvedValue(false) });
    fireEvent.click(screen.getByRole("radio", { name: "3 of 5 stars" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "3 of 5 stars" })).not.toBeDisabled());
    expect(screen.queryByTestId("rating-card-details")).toBeNull();
    expect(screen.queryByTestId("rating-card-thanks")).toBeNull();
    expect(screen.getByRole("radio", { name: "3 of 5 stars" })).toHaveAttribute("aria-checked", "false");
  });

  it("an existing rating opens on thanks, and stays editable and withdrawable", async () => {
    const props = renderCard(stars(), { current: { stepIndex: -1, score: 4, tags: ["Helpful"], comment: "nice" } });
    expect(screen.getByTestId("rating-card-thanks")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("rating-card-change"));
    expect(screen.getByRole("radio", { name: "4 of 5 stars" })).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByRole("radio", { name: "5 of 5 stars" }));
    expect(props.onSubmit).toHaveBeenCalledWith({ score: 5, tags: ["Helpful"], comment: "nice" });
    fireEvent.click(await screen.findByTestId("rating-card-skip"));

    fireEvent.click(await screen.findByTestId("rating-card-withdraw"));
    await waitFor(() => expect(props.onWithdraw).toHaveBeenCalled());
    expect(await screen.findByRole("radiogroup")).toBeInTheDocument();
  });

  it("arrow keys move between the stars; the close button closes", () => {
    const props = renderCard(stars());
    const first = screen.getByRole("radio", { name: "1 of 5 stars" });
    expect(first).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "2 of 5 stars" })).toHaveAttribute("tabindex", "-1");
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowRight" });
    expect(document.activeElement).toBe(screen.getByRole("radio", { name: "2 of 5 stars" }));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowLeft" });
    fireEvent.keyDown(document.activeElement!, { key: "ArrowLeft" });
    expect(document.activeElement).toBe(screen.getByRole("radio", { name: "5 of 5 stars" }));

    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(props.onClose).toHaveBeenCalled();
  });

  it("asks the designer's question when there is one", () => {
    renderCard(stars({ promptText: "Did this help?" }));
    expect(screen.getByRole("radiogroup", { name: "Did this help?" })).toBeInTheDocument();
  });
});
