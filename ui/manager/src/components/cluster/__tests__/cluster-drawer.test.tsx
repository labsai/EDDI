import { useState } from "react";
import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ClusterDrawer } from "@/components/cluster/cluster-drawer";

function Harness({ onClose, startOpen = true }: { onClose: () => void; startOpen?: boolean }) {
  const [open, setOpen] = useState(startOpen);
  return (
    <>
      <button onClick={() => setOpen(true)}>opener</button>
      <ClusterDrawer
        open={open}
        title="Entry"
        onClose={() => {
          onClose();
          setOpen(false);
        }}
      >
        <button>first</button>
        <input aria-label="note" />
        <button disabled>replay (disabled)</button>
      </ClusterDrawer>
    </>
  );
}

/**
 * The drawer holds an admin's in-progress input while the console refreshes
 * behind it, so a parent render (a new inline `onClose`) must not move focus,
 * and Tab must wrap on the last control that CAN take focus.
 */
describe("ClusterDrawer", () => {
  it("keeps focus where it is when the parent re-renders with a new onClose", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<Harness onClose={() => {}} startOpen={false} />);
    await user.click(screen.getByText("opener"));
    const input = await screen.findByLabelText("note");
    await user.click(input);
    expect(input).toHaveFocus();
    // The parent re-renders on every data refresh and passes a fresh inline onClose.
    rerender(<Harness onClose={() => {}} startOpen={false} />);
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    expect(input).toHaveFocus();
  });

  it("traps Tab on the last control that can take focus, skipping a disabled one", async () => {
    renderWithProviders(<Harness onClose={() => {}} />);
    const input = await screen.findByLabelText("note");
    input.focus();
    // false = the dialog's handler prevented the default and wrapped focus itself
    expect(fireEvent.keyDown(input, { key: "Tab" })).toBe(false);
    expect(screen.getByTestId("cluster-drawer-close")).toHaveFocus();
    // Shift+Tab from the first control wraps to the last enabled one
    expect(fireEvent.keyDown(screen.getByTestId("cluster-drawer-close"), { key: "Tab", shiftKey: true })).toBe(false);
    expect(input).toHaveFocus();
  });

  it("closes on Escape through the latest onClose", async () => {
    const user = userEvent.setup();
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderWithProviders(<Harness onClose={first} />);
    await screen.findByLabelText("note");
    rerender(<Harness onClose={second} />);
    await user.keyboard("{Escape}");
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText("note")).toBeNull();
  });
});
