import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, RouterProvider, Route, Routes, createMemoryRouter } from "react-router-dom";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { NavigationGuardDialog } from "@/components/shared/navigation-guard-dialog";

/**
 * Save & leave on the app's one navigation prompt. A page that can save on
 * demand passes `onSave` to `useUnsavedChangesGuard`; the prompt then offers it,
 * and the held navigation goes on only when the save landed.
 */

function Editor({ onSave }: { onSave?: () => Promise<boolean> }) {
  useUnsavedChangesGuard(true, onSave ? { onSave } : undefined);
  return (
    <div>
      <h1>editor</h1>
      <Link to="/other">go other</Link>
    </div>
  );
}

function renderApp(onSave?: () => Promise<boolean>) {
  const router = createMemoryRouter(
    [
      {
        path: "*",
        element: (
          <>
            <NavigationGuardDialog />
            <Routes>
              <Route path="/editor" element={<Editor onSave={onSave} />} />
              <Route path="/other" element={<h1>other</h1>} />
            </Routes>
          </>
        ),
      },
    ],
    { initialEntries: ["/editor"] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

afterEach(cleanup);

describe("useUnsavedChangesGuard — Save & leave", () => {
  it("is not offered by a page that cannot save from the prompt", async () => {
    renderApp();
    await userEvent.click(screen.getByText("go other"));
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(screen.queryByTestId("unsaved-save")).not.toBeInTheDocument();
  });

  it("saves, then lets the navigation through", async () => {
    const onSave = vi.fn(async () => true);
    const router = renderApp(onSave);
    await userEvent.click(screen.getByText("go other"));
    await userEvent.click(await screen.findByTestId("unsaved-save"));

    expect(onSave).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(router.state.location.pathname).toBe("/other"));
  });

  it("stays when the save fails, so nothing is lost", async () => {
    const onSave = vi.fn(async () => false);
    const router = renderApp(onSave);
    await userEvent.click(screen.getByText("go other"));
    await userEvent.click(await screen.findByTestId("unsaved-save"));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(router.state.location.pathname).toBe("/editor");
    expect(screen.getByTestId("unsaved-save")).toBeInTheDocument();
  });
});
