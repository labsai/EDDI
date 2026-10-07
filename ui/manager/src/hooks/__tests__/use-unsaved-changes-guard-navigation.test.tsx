import { describe, it, expect } from "vitest";
import { useState } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryRouter,
  Link,
  RouterProvider,
  useNavigate,
  useSearchParams,
} from "react-router-dom";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { UnsavedChangesPrompt } from "@/components/ui/unsaved-changes-dialog";

/**
 * Review 2026-10-02: the app ran on <BrowserRouter>, which cannot block a
 * navigation, so the guard only covered tab close / reload and every in-app
 * link silently dropped unsaved edits. `main.tsx` now mounts a data router and
 * the guard holds in-app navigation through `useBlocker`.
 */
function Editor() {
  const [dirty, setDirty] = useState(false);
  const guard = useUnsavedChangesGuard(dirty);
  const navigate = useNavigate();
  const [, setSearchParams] = useSearchParams();
  return (
    <div>
      <p data-testid="page">editor</p>
      <button data-testid="edit" onClick={() => setDirty(true)}>edit</button>
      <button data-testid="save" onClick={() => setDirty(false)}>save</button>
      <Link to="/elsewhere" data-testid="link-away">away</Link>
      <button data-testid="nav-away" onClick={() => navigate("/elsewhere")}>nav</button>
      <button
        data-testid="deliberate-away"
        onClick={() => {
          guard.allowNextNavigation();
          navigate("/elsewhere");
        }}
      >
        deliberate
      </button>
      <button data-testid="bump-version" onClick={() => setSearchParams({ version: "2" })}>v2</button>
      <UnsavedChangesPrompt guard={guard} />
    </div>
  );
}

function renderApp() {
  const router = createMemoryRouter(
    [
      { path: "/editor", element: <Editor /> },
      { path: "/elsewhere", element: <p data-testid="page">elsewhere</p> },
    ],
    { initialEntries: ["/editor"] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe("useUnsavedChangesGuard — in-app navigation", () => {
  it("lets a clean page navigate freely", async () => {
    const user = userEvent.setup();
    renderApp();
    await user.click(screen.getByTestId("link-away"));
    expect(screen.getByTestId("page")).toHaveTextContent("elsewhere");
  });

  it("holds a link click while dirty and asks; Cancel stays with the edits", async () => {
    const user = userEvent.setup();
    const router = renderApp();
    await user.click(screen.getByTestId("edit"));
    await user.click(screen.getByTestId("link-away"));

    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");

    await user.click(screen.getByTestId("unsaved-cancel"));
    await waitFor(() => expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument());
    expect(screen.getByTestId("page")).toHaveTextContent("editor");
  });

  it("Discard & Leave lets the held navigation through", async () => {
    const user = userEvent.setup();
    const router = renderApp();
    await user.click(screen.getByTestId("edit"));
    await user.click(screen.getByTestId("nav-away"));
    await user.click(await screen.findByTestId("unsaved-confirm"));
    await waitFor(() => expect(router.state.location.pathname).toBe("/elsewhere"));
  });

  it("does not block a query-string change on the same page (e.g. ?version after a save)", async () => {
    const user = userEvent.setup();
    const router = renderApp();
    await user.click(screen.getByTestId("edit"));
    await user.click(screen.getByTestId("bump-version"));
    expect(router.state.location.search).toBe("?version=2");
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("lets a deliberate navigation through once (after a delete, or the page's own confirm)", async () => {
    const user = userEvent.setup();
    const router = renderApp();
    await user.click(screen.getByTestId("edit"));
    await user.click(screen.getByTestId("deliberate-away"));
    await waitFor(() => expect(router.state.location.pathname).toBe("/elsewhere"));
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("holds the browser's Back button too", async () => {
    const user = userEvent.setup();
    const router = createMemoryRouter(
      [
        { path: "/editor", element: <Editor /> },
        { path: "/elsewhere", element: <p data-testid="page">elsewhere</p> },
      ],
      { initialEntries: ["/elsewhere", "/editor"], initialIndex: 1 },
    );
    render(<RouterProvider router={router} />);
    await user.click(screen.getByTestId("edit"));
    await router.navigate(-1);
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
  });
});
