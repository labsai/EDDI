import { describe, expect, it } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
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
    // The agents come from the mock API's deployed agents. Unfiltered, the
    // sessions can fill every row, so the agent is reached by its name; its
    // conversations match too (their detail is the agent's name).
    fireEvent.change(input, { target: { value: "Contract Review Assistant" } });
    const agent = await screen.findByTestId("quick-switcher-item-agent");
    await waitFor(() => expect(screen.queryByTestId("quick-switcher-item-new")).toBeNull());
    const position = screen.getAllByRole("option").indexOf(agent);
    for (let i = 0; i < position; i++) fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(agent).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/workforce/chat?agentId="));
    expect(screen.getByTestId("where").textContent).not.toContain("conversationId");
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

  it("goes to what is remembered about the person (T2.4), and finds it by name", async () => {
    renderSwitcher();
    press({ key: "k", ctrlKey: true });
    fireEvent.change(await screen.findByTestId("quick-switcher-input"), { target: { value: "remember" } });
    fireEvent.click(await screen.findByTestId("quick-switcher-item-memory"));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\/workforce\/memory$/));
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

  it("also finds older conversations by title on the server (T1.3), each once, titles only", async () => {
    const queries: string[] = [];
    server.use(
      http.get("*/conversationstore/conversations/search", ({ request }) => {
        queries.push(new URL(request.url).searchParams.get("q") ?? "");
        return HttpResponse.json([
          {
            // Also in the sidebar: listed once, as the sidebar's row.
            resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv1",
            createdOn: 1, lastModifiedOn: 2, agentId: "agent1", conversationState: "READY",
            title: "Where is order 1042?",
          },
          {
            // Too old for the sidebar's page: only the search knows it.
            resource: "eddi://ai.labs.conversation/conversationstore/conversations/old-order",
            createdOn: 1, lastModifiedOn: 1, agentId: "agent1", conversationState: "ENDED",
            title: "An old order question",
          },
        ]);
      }),
    );
    renderSwitcher();
    press({ key: "k", ctrlKey: true });
    const input = await screen.findByTestId("quick-switcher-input");
    expect(input).toHaveAttribute("maxLength", "100");
    fireEvent.change(input, { target: { value: "  order " } });
    expect(await screen.findByTestId("quick-switcher-search-hint")).toHaveTextContent("Searches titles only");
    const old = await screen.findByText("An old order question");
    expect(queries).toEqual(["order"]);
    expect(screen.getAllByText("Where is order 1042?")).toHaveLength(1);
    fireEvent.click(old);
    await waitFor(() =>
      expect(screen.getByTestId("where")).toHaveTextContent("/workforce/chat?agentId=agent1&conversationId=old-order"),
    );
  });

  it("says when the title search failed, politely", async () => {
    server.use(
      http.get("*/conversationstore/conversations/search", () => HttpResponse.json({ error: "x" }, { status: 500 })),
    );
    renderSwitcher();
    press({ key: "k", ctrlKey: true });
    fireEvent.change(await screen.findByTestId("quick-switcher-input"), { target: { value: "order" } });
    const status = screen.getByTestId("quick-switcher-search-status");
    expect(status).toHaveAttribute("aria-live", "polite");
    await waitFor(() => expect(status).toHaveTextContent("Searching your older conversations failed."));
  });
});
