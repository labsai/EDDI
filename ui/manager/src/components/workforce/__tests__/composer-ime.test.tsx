import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { BoardInput } from "@/components/workforce/board-input";
import { DiscussionInput } from "@/components/groups/discussion-input";

/**
 * The Enter that confirms an IME composition (Chinese, Japanese, Korean) commits
 * the converted text. Both group composers sent the question on it instead.
 */
describe("group composers and IME composition", () => {
  it("BoardInput ignores the Enter that confirms a composition", () => {
    const onSend = vi.fn();
    renderWithProviders(<BoardInput onSend={onSend} />);
    const box = screen.getByRole("textbox");
    fireEvent.change(box, { target: { value: "你好" } });

    fireEvent.keyDown(box, { key: "Enter", isComposing: true });
    fireEvent.keyDown(box, { key: "Enter", keyCode: 229 });
    expect(onSend).not.toHaveBeenCalled();

    fireEvent.keyDown(box, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("你好");
  });

  it("DiscussionInput ignores the Enter that confirms a composition", () => {
    const onSubmit = vi.fn();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);
    const box = screen.getByTestId("discussion-input");
    fireEvent.change(box, { target: { value: "こんにちは" } });

    fireEvent.keyDown(box, { key: "Enter", isComposing: true });
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.keyDown(box, { key: "Enter" });
    expect(onSubmit).toHaveBeenCalledWith("こんにちは");
  });
});
