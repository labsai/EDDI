import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { AlertDialog } from "@/components/ui/alert-dialog";

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
describe("AlertDialog", () => {
  it("caps its height to the window and scrolls its own content", () => {
    render(
      <AlertDialog open onOpenChange={() => {}} title="Undeploy agent?" description="d" onConfirm={() => {}}>
        <p>content</p>
      </AlertDialog>,
    );

    const classes = (screen.getByRole("dialog").getAttribute("class") ?? "").split(/\s+/);
    expect(classes).toContain("max-h-[calc(100dvh-2rem)]");
    expect(classes).toContain("overflow-y-auto");
  });
});
