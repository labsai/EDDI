import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { BoardInput } from "@/components/workforce/board-input";

/**
 * The Enter that confirms an IME composition (Chinese, Japanese, Korean) commits
 * the converted text. Both group composers sent the question on it instead; the
 * Manager's DiscussionInput has the same case in ui/manager
 * (src/components/groups/__tests__/composer-ime.test.tsx).
 */
describe("the board composer and IME composition", () => {
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
});
