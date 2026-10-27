import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { StepRating } from "./StepRating";
import { parseRatingProfile, type RatingProfile } from "@/api/rating-api";

const profile = (extra: Record<string, unknown> = {}): RatingProfile =>
  parseRatingProfile({ messageRating: true, reasonTags: ["Wrong answer", "Too slow"], comment: true, ...extra })!;

describe("StepRating", () => {
  it("rates up with one tap and marks the thumb pressed", () => {
    const onRate = vi.fn().mockResolvedValue(true);
    const { rerender } = render(<StepRating stepIndex={2} profile={profile()} onRate={onRate} />);

    const up = screen.getByTestId("step-rating-up");
    expect(up).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(up);
    expect(onRate).toHaveBeenCalledWith(2, { score: 1 });
    expect(screen.queryByTestId("step-rating-details")).toBeNull();

    rerender(<StepRating stepIndex={2} profile={profile()} onRate={onRate} rating={{ stepIndex: 2, score: 1, tags: [], comment: null }} />);
    expect(screen.getByTestId("step-rating-up")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("step-rating-2")).toHaveClass("step-rating--rated");
  });

  it("withdraws when the pressed thumb is tapped again", () => {
    const onRate = vi.fn().mockResolvedValue(true);
    render(<StepRating stepIndex={1} profile={profile()} onRate={onRate} rating={{ stepIndex: 1, score: 0, tags: [], comment: null }} />);
    fireEvent.click(screen.getByTestId("step-rating-down"));
    expect(onRate).toHaveBeenCalledWith(1, null);
  });

  it("thumbs down rates at once, then offers reasons and a comment", () => {
    const onRate = vi.fn().mockResolvedValue(true);
    render(<StepRating stepIndex={3} profile={profile()} onRate={onRate} />);

    fireEvent.click(screen.getByTestId("step-rating-down"));
    expect(onRate).toHaveBeenNthCalledWith(1, 3, { score: 0 });
    expect(screen.getByTestId("step-rating-details")).toBeInTheDocument();
    expect(screen.getByTestId("step-rating-down")).toHaveAttribute("aria-expanded", "true");
    // Focus lands in the form, for keyboard users.
    expect(document.activeElement).toHaveTextContent("Wrong answer");

    fireEvent.click(screen.getByRole("button", { name: "Too slow" }));
    expect(screen.getByRole("button", { name: "Too slow" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.change(screen.getByTestId("step-rating-comment"), { target: { value: "took ages" } });
    fireEvent.click(screen.getByTestId("step-rating-send"));

    expect(onRate).toHaveBeenNthCalledWith(2, 3, { score: 0, tags: ["Too slow"], comment: "took ages" });
    expect(screen.queryByTestId("step-rating-details")).toBeNull();
  });

  it("skip and Escape close the form; the thumbs-down already counts", () => {
    const onRate = vi.fn().mockResolvedValue(true);
    render(<StepRating stepIndex={0} profile={profile()} onRate={onRate} />);
    fireEvent.click(screen.getByTestId("step-rating-down"));
    fireEvent.click(screen.getByTestId("step-rating-skip"));
    expect(screen.queryByTestId("step-rating-details")).toBeNull();

    fireEvent.click(screen.getByTestId("step-rating-up"));
    fireEvent.click(screen.getByTestId("step-rating-down"));
    fireEvent.keyDown(screen.getByTestId("step-rating-details"), { key: "Escape" });
    expect(screen.queryByTestId("step-rating-details")).toBeNull();
    expect(onRate).toHaveBeenCalledTimes(3);
  });

  it("opens no form when the agent takes neither reasons nor comments", () => {
    const onRate = vi.fn().mockResolvedValue(true);
    render(<StepRating stepIndex={0} profile={profile({ reasonTags: [], comment: false })} onRate={onRate} />);
    fireEvent.click(screen.getByTestId("step-rating-down"));
    expect(screen.queryByTestId("step-rating-details")).toBeNull();
    expect(screen.getByTestId("step-rating-down")).not.toHaveAttribute("aria-expanded");
  });

  it("has labelled buttons for screen readers", () => {
    render(<StepRating stepIndex={0} profile={profile()} onRate={vi.fn()} />);
    expect(screen.getByRole("group", { name: "Rate this reply" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Good reply" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Bad reply" })).toBeInTheDocument();
  });
});
