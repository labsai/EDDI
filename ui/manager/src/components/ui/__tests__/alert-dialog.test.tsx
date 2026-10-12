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

  it("keeps Cancel usable while pending when the dialog opted out of locking", () => {
    renderWithProviders(
      <AlertDialog
        open
        onOpenChange={vi.fn()}
        title="t"
        description="d"
        onConfirm={vi.fn()}
        isPending
        lockWhilePending={false}
      />,
    );
    expect(screen.getByTestId("alert-dialog-cancel")).toBeEnabled();
    expect(screen.getByTestId("alert-dialog-confirm")).toBeDisabled();
  });

  it("ignores Escape and the X while the action is pending", async () => {
    const user = userEvent.setup();
    const onOpenChange = renderDialog({ isPending: true });
    await user.keyboard("{Escape}");
    await user.click(screen.getByText("Close").closest("button")!);
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  /**
   * The confirm dialog is fixed in place and centred with a transform, so the
   * page behind it cannot scroll it into view. Without a height cap and its own
   * scroller, content taller than the window — an undeploy prompt on a landscape
   * phone measured 456px of dialog in a 375px window — ran off both edges: Close
   * above the screen, Cancel and the action cut off below it.
   *
   * Class-based, as in workforce-scroll-contract.test.tsx: jsdom loads no
   * stylesheet, so computed overflow is always "visible" here, and the Tailwind
   * classes are the styling contract.
   */
  it("caps its height to the window and scrolls its own content", () => {
    renderWithProviders(
      <AlertDialog open onOpenChange={() => {}} title="Undeploy agent?" description="d" onConfirm={() => {}}>
        <p>content</p>
      </AlertDialog>,
    );

    const classes = (screen.getByRole("dialog").getAttribute("class") ?? "").split(/\s+/);
    expect(classes).toContain("max-h-[calc(100dvh-2rem)]");
    expect(classes).toContain("overflow-y-auto");
  });
});
