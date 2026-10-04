import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AlertDialog } from "@/components/ui/alert-dialog";

function renderDialog(props: { isPending?: boolean; onOpenChange?: (o: boolean) => void } = {}) {
  const onOpenChange = props.onOpenChange ?? vi.fn();
  renderWithProviders(
    <AlertDialog
      open
      onOpenChange={onOpenChange}
      title="Delete thing?"
      description="This cannot be undone."
      onConfirm={vi.fn()}
      isPending={props.isPending}
    />,
  );
  return onOpenChange;
}

describe("AlertDialog", () => {
  it("uses translated default labels", () => {
    renderDialog();
    expect(screen.getByTestId("alert-dialog-cancel")).toHaveTextContent("Cancel");
    expect(screen.getByTestId("alert-dialog-confirm")).toHaveTextContent("Delete");
    expect(screen.getByText("Close")).toHaveClass("sr-only");
  });

  it("closes on Escape when idle", async () => {
    const onOpenChange = renderDialog();
    await userEvent.setup().keyboard("{Escape}");
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("ignores Escape and the X while the action is pending", async () => {
    const user = userEvent.setup();
    const onOpenChange = renderDialog({ isPending: true });
    await user.keyboard("{Escape}");
    await user.click(screen.getByText("Close").closest("button")!);
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});
