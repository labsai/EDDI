import { useEffect, useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  Link,
  Route,
  RouterProvider,
  Routes,
  createMemoryRouter,
  useNavigate,
} from "react-router-dom";
import { allowNextNavigation, useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { NavigationGuardDialog } from "@/components/shared/navigation-guard-dialog";

/**
 * In-app navigation under a DATA router — the layer `beforeunload` cannot cover.
 * `useUnsavedChangesGuard` used to listen for `beforeunload` only, so a sidebar
 * click, the breadcrumb or the Back button discarded edits without a word.
 */

function Editor({ dirty }: { dirty: boolean }) {
  useUnsavedChangesGuard(dirty);
  const navigate = useNavigate();
  // Mount the clean guard in a LATER commit, so its registration is the last one.
  const [late, setLate] = useState(false);
  useEffect(() => setLate(true), []);
  return (
    <div>
      <h1>editor</h1>
      <Link to="/other">go other</Link>
      <Link to="/editor?tab=2">same page</Link>
      {late && <CleanGuard />}
      <button onClick={() => navigate("/other")}>programmatic</button>
      <button
        onClick={() => {
          allowNextNavigation();
          navigate("/other");
        }}
      >
        programmatic allowed
      </button>
    </div>
  );
}

/** A second guarded component, always clean, mounted AFTER the dirty one. */
function CleanGuard() {
  useUnsavedChangesGuard(false);
  return null;
}

function renderApp(dirty: boolean) {
  const router = createMemoryRouter(
    [
      {
        path: "*",
        element: (
          <>
            <NavigationGuardDialog />
            <RouterRoutes dirty={dirty} />
          </>
        ),
      },
    ],
    { initialEntries: ["/editor"] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

function RouterRoutes({ dirty }: { dirty: boolean }) {
  return (
    <Routes>
      <Route path="/editor" element={<Editor dirty={dirty} />} />
      <Route path="/other" element={<h1>other page</h1>} />
    </Routes>
  );
}

describe("useUnsavedChangesGuard — in-app navigation", () => {
  afterEach(() => cleanup());

  it("lets a clean page navigate freely", async () => {
    const router = renderApp(false);
    await userEvent.click(screen.getByText("go other"));
    expect(router.state.location.pathname).toBe("/other");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("holds a dirty page's navigation and asks first", async () => {
    const router = renderApp(true);
    await userEvent.click(screen.getByText("go other"));

    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
  });

  it("Stay keeps the user on the page", async () => {
    const router = renderApp(true);
    await userEvent.click(screen.getByText("go other"));
    await userEvent.click(await screen.findByRole("button", { name: "Stay" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
    expect(screen.getByText("editor")).toBeInTheDocument();
  });

  it("Discard lets the held navigation through", async () => {
    const router = renderApp(true);
    await userEvent.click(screen.getByText("go other"));
    await userEvent.click(await screen.findByTestId("unsaved-confirm"));

    expect(router.state.location.pathname).toBe("/other");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("holds a programmatic navigate() too", async () => {
    const router = renderApp(true);
    await userEvent.click(screen.getByText("programmatic"));
    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
  });

  it("still holds navigation when a clean guard mounts after the dirty one", async () => {
    // A router consults only the LAST registered blocker, so per-hook blockers let
    // the clean instance silently take over and leave the dirty page unprotected.
    const router = renderApp(true);
    await userEvent.click(screen.getByText("go other"));
    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
  });

  it("does not hold a change that stays on the same path", async () => {
    const router = renderApp(true);
    await userEvent.click(screen.getByText("same page"));
    expect(router.state.location.search).toBe("?tab=2");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("allowNextNavigation() lets exactly one navigation through", async () => {
    const router = renderApp(true);
    await userEvent.click(screen.getByText("programmatic allowed"));
    expect(router.state.location.pathname).toBe("/other");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("holds the browser's Back button too", async () => {
    const router = createMemoryRouter(
      [
        {
          path: "*",
          element: (
            <>
              <NavigationGuardDialog />
              <Routes>
                <Route path="/editor" element={<Editor dirty />} />
                <Route path="/other" element={<h1>other page</h1>} />
              </Routes>
            </>
          ),
        },
      ],
      { initialEntries: ["/other", "/editor"], initialIndex: 1 },
    );
    render(<RouterProvider router={router} />);
    await router.navigate(-1);

    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
  });

  it("a clean guard in a sibling component, mounted after the dirty one, does not unprotect it", async () => {
    // Regression for the single-blocker contract: a router consults only the LAST
    // blocker registered, so if each hook called `useBlocker` itself, mounting any
    // clean guard later (here a separate sibling, not a child of the dirty page)
    // would take over and let the navigation through with the edits unsaved.
    function DirtyPage() {
      useUnsavedChangesGuard(true);
      return <Link to="/other">go other</Link>;
    }
    function LateCleanSibling() {
      const [show, setShow] = useState(false);
      useEffect(() => setShow(true), []);
      return show ? <CleanGuard /> : null;
    }
    const router = createMemoryRouter(
      [
        {
          path: "*",
          element: (
            <>
              <NavigationGuardDialog />
              <Routes>
                <Route
                  path="/editor"
                  element={
                    <>
                      <DirtyPage />
                      <LateCleanSibling />
                    </>
                  }
                />
                <Route path="/other" element={<h1>other page</h1>} />
              </Routes>
            </>
          ),
        },
      ],
      { initialEntries: ["/editor"] },
    );
    render(<RouterProvider router={router} />);
    // Let the late sibling mount and register before navigating.
    await screen.findByText("go other");
    await new Promise((resolve) => setTimeout(resolve, 0));

    await userEvent.click(screen.getByText("go other"));

    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/editor");
  });
});
