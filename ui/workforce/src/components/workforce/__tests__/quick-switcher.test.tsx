import { describe, expect, it } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { QuickSwitcher } from "@/components/workforce/quick-switcher";
import { isNewChatState } from "@/lib/workforce-keyboard";

/** T1.10 — ⌘K / Ctrl+K quick switcher and ⌘⇧O / Ctrl+Shift+O new chat. */

function Where() {
  const location = useLocation();
  return (
    <div data-testid="where" data-new-chat={String(isNewChatState(location.state))}>
      {location.pathname}
      {location.search}
    </div>
  );
}

function renderSwitcher(route = "/workforce/analytics") {
  return renderWithProviders(
    <>
      <button data-testid="somewhere">somewhere</button>
      <QuickSwitcher />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </>,
    { initialRoute: route },
  );
}

const press = (init: KeyboardEventInit) =>
  act(() => {
    window.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, ...init }));
  });

describe("QuickSwitcher", () => {
  it("opens on Ctrl+K (and ⌘K), focused on its search box", async () => {
    renderSwitcher();
    expect(screen.queryByTestId("quick-switcher")).toBeNull();
    press({ key: "k", ctrlKey: true });
    const input = await screen.findByTestId("quick-switcher-input");
    await waitFor(() => expect(input).toHaveFocus());
    expect(screen.getByRole("dialog")).toHaveAttribute("aria-modal", "true");
    expect(screen.getByTestId("quick-switcher-item-new")).toBeInTheDocument();
  });

  it("filters, moves with the arrows and opens a chat with an agent on Enter", async () => {
    renderSwitcher();
    press({ key: "k", metaKey: true });
    const input = await screen.findByTestId("quick-switcher-input");
    // The agents come from the mock API's deployed agents.
    await screen.findAllByTestId("quick-switcher-item-agent");
    const firstAgent = screen.getAllByTestId("quick-switcher-item-agent")[0]!;
    const name = firstAgent.textContent!.replace("Chat with this agent", "").trim();
    fireEvent.change(input, { target: { value: name } });
    await waitFor(() => expect(screen.queryByTestId("quick-switcher-item-new")).toBeNull());
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/workforce/chat?agentId="));
    expect(screen.queryByTestId("quick-switcher")).toBeNull();
  });

  it("says when nothing matches", async () => {
    renderSwitcher();
    press({ key: "k", ctrlKey: true });
    fireEvent.change(await screen.findByTestId("quick-switcher-input"), { target: { value: "zzz-no-such-thing" } });
    expect(await screen.findByTestId("quick-switcher-empty")).toBeInTheDocument();
  });

  it("closes on Esc and puts focus back where it was", async () => {
    renderSwitcher();
    const before = screen.getByTestId("somewhere");
    before.focus();
    press({ key: "k", ctrlKey: true });
    const input = await screen.findByTestId("quick-switcher-input");
    fireEvent.keyDown(input, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("quick-switcher")).toBeNull());
    await waitFor(() => expect(before).toHaveFocus());
  });

  it("New chat goes Home asking for the composer", async () => {
    renderSwitcher();
    press({ key: "k", ctrlKey: true });
    fireEvent.click(await screen.findByTestId("quick-switcher-item-new"));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\/workforce$/));
    expect(screen.getByTestId("where")).toHaveAttribute("data-new-chat", "true");
  });

  it("Ctrl+Shift+O starts a new chat from anywhere", async () => {
    renderSwitcher("/workforce/chat");
    press({ key: "O", ctrlKey: true, shiftKey: true });
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\/workforce$/));
    expect(screen.getByTestId("where")).toHaveAttribute("data-new-chat", "true");
  });

  it("ignores K without a modifier, and Ctrl+Alt+K", () => {
    renderSwitcher();
    press({ key: "k" });
    press({ key: "k", ctrlKey: true, altKey: true });
    expect(screen.queryByTestId("quick-switcher")).toBeNull();
  });
});
