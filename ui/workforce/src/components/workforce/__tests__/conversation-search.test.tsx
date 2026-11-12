import { describe, it, expect } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { SessionList } from "@/components/workforce/session-list";

/**
 * T1.3 — "Search my conversations" in the Workforce sidebar, against the MSW
 * backend's title search: results replace the list while the box holds text,
 * titles only and said so, keyboard and screen-reader friendly.
 */

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname + location.search}</div>;
}

function renderList() {
  return renderWithProviders(
    <>
      <SessionList />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </>,
    { initialRoute: "/workforce" },
  );
}

const input = () => screen.getByTestId("conversation-search-input") as HTMLInputElement;

describe("ConversationSearch", () => {
  it("says it searches titles only, and caps the text at what the backend accepts", async () => {
    renderList();
    await screen.findByTestId("session-group-pinned");
    const hint = screen.getByTestId("conversation-search-hint");
    expect(hint).toHaveTextContent("Searches titles only, not what was said.");
    expect(input()).toHaveAttribute("aria-describedby", hint.id);
    expect(input()).toHaveAccessibleName("Search my conversations");
    expect(input()).toHaveAttribute("maxLength", "100");
  });

  it("replaces the list with the title matches and announces how many it found", async () => {
    renderList();
    await screen.findByTestId("session-group-pinned");
    fireEvent.change(input(), { target: { value: "ORDER" } });
    expect(await screen.findByTestId("conversation-search-loading")).toBeInTheDocument();

    const results = await screen.findByTestId("conversation-search-results");
    const option = await within(results).findByTestId("conversation-search-result-conv1");
    expect(option).toHaveTextContent("Where is order 1042?");
    expect(option).toHaveAttribute("role", "option");
    expect(screen.queryByTestId("session-group-pinned")).toBeNull();
    const status = screen.getByTestId("conversation-search-status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("Conversations found: 1");
    expect(input()).toHaveAttribute("aria-expanded", "true");
  });

  it("opens the highlighted result with the arrows and Enter", async () => {
    renderList();
    await screen.findByTestId("session-group-pinned");
    // "r" is in both titles: "Where is order 1042?" and "Refund for a damaged parcel".
    fireEvent.change(input(), { target: { value: "r" } });
    await screen.findByTestId("conversation-search-result-conv3");
    const options = screen.getAllByRole("option");
    expect(options.length).toBeGreaterThanOrEqual(2);
    expect(options[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input(), { key: "ArrowDown" });
    expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true");
    expect(input()).toHaveAttribute("aria-activedescendant", screen.getAllByRole("option")[1]!.id);
    const target = screen.getAllByRole("option")[1]!.getAttribute("data-testid")!.replace("conversation-search-result-", "");
    fireEvent.keyDown(input(), { key: "Enter" });
    await waitFor(() =>
      expect(screen.getByTestId("where")).toHaveTextContent(`/workforce/chat?agentId=agent1&conversationId=${target}`),
    );
  });

  it("says so when no title matches, and Esc brings the list back", async () => {
    renderList();
    await screen.findByTestId("session-group-pinned");
    fireEvent.change(input(), { target: { value: "no such title" } });
    expect(await screen.findByTestId("conversation-search-empty")).toHaveTextContent("No conversation titles match.");
    expect(screen.getByTestId("conversation-search-status")).toHaveTextContent("No conversation titles match.");
    fireEvent.keyDown(input(), { key: "Escape" });
    expect(input().value).toBe("");
    expect(await screen.findByTestId("session-group-pinned")).toBeInTheDocument();
  });

  it("offers a retry when the search fails", async () => {
    let fail = true;
    server.use(
      http.get("*/conversationstore/conversations/search", () =>
        fail ? HttpResponse.json({ error: "boom" }, { status: 500 }) : HttpResponse.json([]),
      ),
    );
    renderList();
    await screen.findByTestId("session-group-pinned");
    fireEvent.change(input(), { target: { value: "order" } });
    const retry = await screen.findByTestId("conversation-search-error");
    expect(screen.getByTestId("conversation-search-status")).toHaveTextContent("The search failed.");
    fail = false;
    fireEvent.click(retry);
    expect(await screen.findByTestId("conversation-search-empty")).toBeInTheDocument();
  });

  it("loads more while pages come back full", async () => {
    const pages: number[] = [];
    const conv = (n: number) => ({
      resource: `eddi://ai.labs.conversation/conversationstore/conversations/s${n}`,
      createdOn: 1,
      lastModifiedOn: 1000 - n,
      agentId: "agent1",
      conversationState: "READY",
      title: `Trip ${n}`,
    });
    server.use(
      http.get("*/conversationstore/conversations/search", ({ request }) => {
        const url = new URL(request.url);
        const index = Number(url.searchParams.get("index"));
        const limit = Number(url.searchParams.get("limit"));
        pages.push(index);
        // Two full pages, then a short one.
        const count = index < 2 ? limit : 3;
        return HttpResponse.json(Array.from({ length: count }, (_, i) => conv(index * limit + i)));
      }),
    );
    renderList();
    await screen.findByTestId("session-group-pinned");
    fireEvent.change(input(), { target: { value: "trip" } });
    await screen.findByTestId("conversation-search-result-s0");
    fireEvent.click(screen.getByTestId("conversation-search-more"));
    await screen.findByTestId("conversation-search-result-s20");
    fireEvent.click(screen.getByTestId("conversation-search-more"));
    await screen.findByTestId("conversation-search-result-s42");
    expect(screen.queryByTestId("conversation-search-more")).toBeNull();
    expect(pages).toEqual([0, 1, 2]);
    expect(screen.getAllByRole("option")).toHaveLength(43);
  });
});
