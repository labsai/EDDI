import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { DiscussionInput } from "@/components/groups/discussion-input";

/**
 * The Enter that confirms an IME composition (Chinese, Japanese, Korean) commits
 * the converted text. Both group composers sent the question on it instead;
 * Workforce's BoardInput has the same case in ui/workforce
 * (src/components/workforce/__tests__/composer-ime.test.tsx).
 */
describe("group composers and IME composition", () => {
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
