import { describe, it, expect, vi, afterEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter, Link, Route, Routes, useNavigate } from "react-router-dom";
import { useState } from "react";
import { UnsavedChangesNavigationGuard } from "@/components/layout/unsaved-changes-navigation-guard";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { requestNavigation } from "@/lib/unsaved-changes-registry";

/**
 * The in-app half of the unsaved-changes guard, under a real BrowserRouter so
 * the back button goes through `window.history` as it does in the app.
 */

let onSave: ReturnType<typeof vi.fn<() => Promise<boolean>>> | undefined;

function Editor() {
  const [dirty, setDirty] = useState(false);
  const navigate = useNavigate();
  useUnsavedChangesGuard(dirty, onSave ? { onSave } : undefined);
  return (
    <div>
      <p data-testid="page">editor</p>
      <button onClick={() => setDirty(true)}>edit</button>
      <Link to="/elsewhere">elsewhere</Link>
      <a href="https://example.com/out">external</a>
      <button onClick={() => requestNavigation(() => navigate("/elsewhere"))}>palette</button>
    </div>
  );
}

function App() {
  return (
    <>
      <Routes>
        <Route path="/" element={<Link to="/editor">open editor</Link>} />
        <Route path="/editor" element={<Editor />} />
        <Route path="/elsewhere" element={<p data-testid="page">elsewhere</p>} />
      </Routes>
      <UnsavedChangesNavigationGuard />
    </>
  );
}

async function openDirtyEditor() {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/");
  render(
    <BrowserRouter>
      <App />
    </BrowserRouter>,
  );
  await user.click(screen.getByText("open editor"));
  await user.click(screen.getByText("edit"));
  return user;
}

afterEach(() => {
  onSave = undefined;
});

describe("UnsavedChangesNavigationGuard", () => {
  it("lets a link through when nothing is unsaved", async () => {
    const user = userEvent.setup();
    window.history.replaceState(null, "", "/");
    render(
      <BrowserRouter>
        <App />
      </BrowserRouter>,
    );
    await user.click(screen.getByText("open editor"));
    await user.click(screen.getByText("elsewhere"));
    expect(screen.getByTestId("page")).toHaveTextContent("elsewhere");
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("holds an in-app link while edits are unsaved; Keep editing stays", async () => {
    const user = await openDirtyEditor();
    await user.click(screen.getByText("elsewhere"));

    expect(screen.getByTestId("page")).toHaveTextContent("editor");
    expect(screen.getByTestId("unsaved-confirm")).toBeInTheDocument();

    await user.click(screen.getByTestId("unsaved-cancel"));
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
    expect(screen.getByTestId("page")).toHaveTextContent("editor");
  });

  it("Discard changes goes where the link pointed", async () => {
    const user = await openDirtyEditor();
    await user.click(screen.getByText("elsewhere"));
    await user.click(screen.getByTestId("unsaved-confirm"));
    expect(screen.getByTestId("page")).toHaveTextContent("elsewhere");
  });

  it("offers Save & leave only when the page can save, and leaves once it has", async () => {
    onSave = vi.fn(async () => true);
    const user = await openDirtyEditor();
    await user.click(screen.getByText("elsewhere"));

    await user.click(screen.getByTestId("unsaved-save"));
    expect(onSave).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByTestId("page")).toHaveTextContent("elsewhere"));
  });

  it("stays when Save & leave fails", async () => {
    onSave = vi.fn(async () => false);
    const user = await openDirtyEditor();
    await user.click(screen.getByText("elsewhere"));

    await user.click(screen.getByTestId("unsaved-save"));
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("page")).toHaveTextContent("editor");
    expect(screen.getByTestId("unsaved-save")).toBeInTheDocument();
  });

  it("has no Save & leave button for a page that cannot save from the prompt", async () => {
    const user = await openDirtyEditor();
    await user.click(screen.getByText("elsewhere"));
    expect(screen.queryByTestId("unsaved-save")).not.toBeInTheDocument();
  });

  it("holds programmatic navigation routed through requestNavigation", async () => {
    const user = await openDirtyEditor();
    await user.click(screen.getByText("palette"));
    expect(screen.getByTestId("page")).toHaveTextContent("editor");
    await user.click(screen.getByTestId("unsaved-confirm"));
    expect(screen.getByTestId("page")).toHaveTextContent("elsewhere");
  });

  it("holds the browser back button and replays it on Discard", async () => {
    const user = await openDirtyEditor();

    await act(async () => {
      window.history.back();
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(screen.getByTestId("page")).toHaveTextContent("editor");
    expect(window.location.pathname).toBe("/editor");

    await user.click(screen.getByTestId("unsaved-confirm"));
    await waitFor(() => expect(window.location.pathname).toBe("/"));
    expect(screen.getByText("open editor")).toBeInTheDocument();
  });
});
