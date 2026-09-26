import { describe, it, expect, vi, beforeAll, afterAll } from "vitest";
import { render, screen } from "@testing-library/react";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { userEvent } from "@/test/test-utils";
import { SuspendedOutlet } from "../suspended-outlet";

/**
 * Per-route error boundary: a page that throws must not take the shell with it.
 * With only the root boundary in `app.tsx`, one bad config shape replaced the
 * sidebar, top bar and chat drawer with the error fallback.
 */

const originalError = console.error;
beforeAll(() => {
  console.error = vi.fn();
});
afterAll(() => {
  console.error = originalError;
});

function Broken(): never {
  throw new Error("unexpected config shape");
}

function Layout() {
  return (
    <div>
      <nav data-testid="chrome">
        <Link to="/fine" data-testid="nav-fine">
          fine
        </Link>
      </nav>
      <SuspendedOutlet />
    </div>
  );
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/broken" element={<Broken />} />
          <Route path="/fine" element={<span data-testid="fine-page">fine</span>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("SuspendedOutlet error boundary", () => {
  it("contains a page error inside the page area, keeping the chrome", () => {
    renderAt("/broken");
    expect(screen.getByTestId("error-boundary-fallback")).toHaveTextContent(
      "unexpected config shape",
    );
    expect(screen.getByTestId("chrome")).toBeInTheDocument();
  });

  it("recovers when the user navigates elsewhere", async () => {
    const user = userEvent.setup();
    renderAt("/broken");
    await user.click(screen.getByTestId("nav-fine"));
    expect(screen.getByTestId("fine-page")).toBeInTheDocument();
    expect(screen.queryByTestId("error-boundary-fallback")).not.toBeInTheDocument();
  });
});
