import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { UnsavedChangesPrompt } from "@/components/ui/unsaved-changes-dialog";

/**
 * The in-app "leave with unsaved changes?" prompt. A page that can save from it
 * passes `onSave`, which adds Save & leave; the held navigation goes on only
 * when that save actually landed.
 */
function heldGuard() {
  return { blocked: true, proceed: vi.fn(), stay: vi.fn() };
}

describe("UnsavedChangesPrompt", () => {
  it("renders nothing while no navigation is held", () => {
    render(<UnsavedChangesPrompt guard={{ blocked: false, proceed: vi.fn(), stay: vi.fn() }} />);
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("without onSave offers only stay and discard", async () => {
    const guard = heldGuard();
    render(<UnsavedChangesPrompt guard={guard} />);
    expect(screen.queryByTestId("unsaved-save")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("unsaved-cancel"));
    expect(guard.stay).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByTestId("unsaved-confirm"));
    expect(guard.proceed).toHaveBeenCalledTimes(1);
  });

  it("Save & leave saves, then lets the navigation through", async () => {
    const guard = heldGuard();
    const onSave = vi.fn(async () => true);
    render(<UnsavedChangesPrompt guard={guard} onSave={onSave} />);

    await userEvent.click(screen.getByTestId("unsaved-save"));
    expect(onSave).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(guard.proceed).toHaveBeenCalledTimes(1));
    expect(guard.stay).not.toHaveBeenCalled();
  });

  it("stays when the save fails, so nothing is lost", async () => {
    const guard = heldGuard();
    const onSave = vi.fn(async () => false);
    render(<UnsavedChangesPrompt guard={guard} onSave={onSave} />);

    await userEvent.click(screen.getByTestId("unsaved-save"));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(guard.proceed).not.toHaveBeenCalled();
    expect(screen.getByTestId("unsaved-save")).toBeInTheDocument();
  });
});
