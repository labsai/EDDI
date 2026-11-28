import { describe, expect, it, vi } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { BoardInput } from "@/components/workforce/board-input";

/** A promise the test settles by hand — stands in for "the server answered". */
function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

// The Manager's composers hold the same contract
// (ui/manager/src/components/groups/__tests__/composer-draft-and-transcript-follow.test.tsx).
describe("BoardInput keeps the draft until the request is accepted", () => {
  it("keeps the message when the send is refused", async () => {
    const gate = deferred<boolean>();
    const onSend = vi.fn(() => gate.promise);
    const user = userEvent.setup();
    renderWithProviders(<BoardInput onSend={onSend} />);

    const box = screen.getByRole("textbox");
    await user.type(box, "Plan the launch");
    await user.click(screen.getByTestId("board-send"));
    expect(box).toHaveValue("Plan the launch");

    await act(async () => gate.resolve(false));
    await waitFor(() => expect(box).not.toBeDisabled());
    expect(box).toHaveValue("Plan the launch");
  });
});
