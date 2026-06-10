import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { DiscussionInput } from "@/components/groups/discussion-input";

describe("DiscussionInput", () => {
  it("renders the textarea", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.getByTestId("discussion-input")).toBeInTheDocument();
  });

  it("renders the submit button", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.getByTestId("start-discussion-btn")).toBeInTheDocument();
  });

  it("submit button is disabled when input is empty", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
  });

  it("submit button enables when text is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    await user.type(screen.getByTestId("discussion-input"), "Test question");
    expect(screen.getByTestId("start-discussion-btn")).not.toBeDisabled();
  });

  it("calls onSubmit with trimmed question", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "  Test question  ");
    await user.click(screen.getByTestId("start-discussion-btn"));

    expect(onSubmit).toHaveBeenCalledWith("Test question");
  });

  it("clears input after submitting", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    const input = screen.getByTestId("discussion-input");
    await user.type(input, "Question");
    await user.click(screen.getByTestId("start-discussion-btn"));

    expect(input).toHaveValue("");
  });

  it("sends on Enter key", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "Enter test{Enter}");
    expect(onSubmit).toHaveBeenCalledWith("Enter test");
  });

  it("does not send on Shift+Enter", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(
      screen.getByTestId("discussion-input"),
      "Line 1{Shift>}{Enter}{/Shift}Line 2"
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("disables textarea when disabled prop is true", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} disabled />);
    expect(screen.getByTestId("discussion-input")).toBeDisabled();
  });

  it("disables textarea when isLoading is true", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} isLoading />);
    expect(screen.getByTestId("discussion-input")).toBeDisabled();
  });

  it("shows spinner when isLoading", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} isLoading />);
    const btn = screen.getByTestId("start-discussion-btn");
    const spinner = btn.querySelector(".animate-spin");
    expect(spinner).not.toBeNull();
  });

  it("shows keyboard hint when text is entered", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    await user.type(screen.getByTestId("discussion-input"), "text");
    expect(screen.getByText(/Enter to send/)).toBeInTheDocument();
  });

  it("does not show keyboard hint when input is empty", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    expect(screen.queryByText(/Enter to send/)).not.toBeInTheDocument();
  });

  it("has expand button", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    const expandIcon = document.querySelector("svg.lucide-expand");
    expect(expandIcon).not.toBeNull();
  });

  it("does not submit empty/whitespace-only input", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "   ");
    expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
  });
});
