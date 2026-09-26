import { describe, it, expect } from "vitest";
import { act, render, screen } from "@testing-library/react";
import {
  createMemoryRouter,
  Link,
  Outlet,
  RouterProvider,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { userEvent } from "@/test/test-utils";
import { UnsavedChangesNavigationGuard } from "../unsaved-changes-navigation-guard";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { allowNextNavigation } from "@/lib/unsaved-changes";

/**
 * UI High 14: in-app navigation silently discarded dirty editors, because the
 * guard only listened for `beforeunload`. These drive a real data router, the
 * same kind `main.tsx` creates.
 */

function Editor({ dirty }: { dirty: boolean }) {
  useUnsavedChangesGuard(dirty);
  const navigate = useNavigate();
  return (
    <div>
      <span data-testid="editor">editor</span>
      <Link to="/other" data-testid="leave-link">
        leave
      </Link>
      <Link to="/edit?version=2" data-testid="query-link">
        version 2
      </Link>
      <button
        data-testid="deliberate-exit"
        onClick={() => {
          allowNextNavigation();
          navigate("/other");
        }}
      >
        deleted, leave
      </button>
    </div>
  );
}

function Shell() {
  const location = useLocation();
  return (
    <>
      <span data-testid="location">{location.pathname + location.search}</span>
      <Outlet />
      <UnsavedChangesNavigationGuard />
    </>
  );
}

function renderRouter(dirty: boolean) {
  const router = createMemoryRouter(
    [
      {
        element: <Shell />,
        children: [
          { path: "/edit", element: <Editor dirty={dirty} /> },
          { path: "/other", element: <span data-testid="other">other</span> },
        ],
      },
    ],
    { initialEntries: ["/start", "/edit"], initialIndex: 1 },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe("UnsavedChangesNavigationGuard", () => {
  it("lets a clean editor navigate without asking", async () => {
    const user = userEvent.setup();
    renderRouter(false);
    await user.click(screen.getByTestId("leave-link"));
    expect(await screen.findByTestId("other")).toBeInTheDocument();
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("blocks a link away from a dirty editor and asks first", async () => {
    const user = userEvent.setup();
    renderRouter(true);
    await user.click(screen.getByTestId("leave-link"));

    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/edit");
    expect(screen.getByTestId("editor")).toBeInTheDocument();
  });

  it("stays put on Cancel and leaves on Discard & Leave", async () => {
    const user = userEvent.setup();
    renderRouter(true);

    await user.click(screen.getByTestId("leave-link"));
    await user.click(await screen.findByTestId("unsaved-cancel"));
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/edit");

    await user.click(screen.getByTestId("leave-link"));
    await user.click(await screen.findByTestId("unsaved-confirm"));
    expect(await screen.findByTestId("other")).toBeInTheDocument();
  });

  it("does not block a query-string change on the same page", async () => {
    const user = userEvent.setup();
    renderRouter(true);
    await user.click(screen.getByTestId("query-link"));
    expect(screen.getByTestId("location")).toHaveTextContent("/edit?version=2");
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("lets a deliberate exit through once, without asking", async () => {
    const user = userEvent.setup();
    renderRouter(true);
    await user.click(screen.getByTestId("deliberate-exit"));
    expect(await screen.findByTestId("other")).toBeInTheDocument();
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });
});

describe("UnsavedChangesNavigationGuard — history", () => {
  it("blocks Back (a POP navigation) from a dirty editor", async () => {
    const router = renderRouter(true);
    await act(async () => {
      await router.navigate(-1);
    });
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/edit");
  });
});
