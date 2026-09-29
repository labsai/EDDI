import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceHistory } from "../workforce-history";

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

function conversations(count: number) {
  const now = Date.now();
  return Array.from({ length: count }, (_, i) => ({
    id: `gc-${i}`,
    groupId: "board1",
    userId: "me",
    state: "COMPLETED",
    originalQuestion: `Question number ${i}`,
    created: new Date(now - i * 60_000).toISOString(),
    lastModified: new Date(now - i * 60_000).toISOString(),
  }));
}

describe("WorkforceHistory — paging and the group's version", () => {
  /**
   * The list pages through the caller's own conversations by (index, limit) —
   * the contract the backend keeps once the owner filter runs in the query. The
   * history then offers "Load more" exactly while a page comes back full.
   */
  it("loads the next page of the caller's conversations", async () => {
    const all = conversations(25);
    const limits: number[] = [];
    server.use(
      http.get("*/groups/:groupId/conversations", ({ request }) => {
        const url = new URL(request.url);
        const index = Number(url.searchParams.get("index") ?? 0);
        const limit = Number(url.searchParams.get("limit") ?? 20);
        limits.push(limit);
        return HttpResponse.json(all.slice(index, index + limit));
      }),
    );
    renderPage("/workforce/board1/history", <WorkforceHistory />, "/workforce/:boardId/history");

    expect(await screen.findByText("Question number 19")).toBeInTheDocument();
    expect(screen.queryByText("Question number 20")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /load more/i }));

    expect(await screen.findByText("Question number 24")).toBeInTheDocument();
    expect(limits).toEqual([20, 40]);
    // A short page means there is nothing further to load.
    expect(screen.queryByRole("button", { name: /load more/i })).not.toBeInTheDocument();
  });

  /**
   * The current version used to be learned from the enriched descriptor
   * listing — the descriptors of 200 groups plus a config read for each — to
   * find one number that `currentversion` answers directly.
   */
  it("reads the group's current version directly, not via every group's config", async () => {
    let listed = false;
    const versionsRead: string[] = [];
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        listed = true;
        return HttpResponse.json([]);
      }),
      http.get("*/groupstore/groups/:id/currentversion", () => HttpResponse.json(7)),
      http.get("*/groupstore/groups/:id", ({ request }) => {
        versionsRead.push(new URL(request.url).searchParams.get("version") ?? "none");
        return HttpResponse.json({ name: "Board", members: [], style: "ROUND_TABLE", maxRounds: 1, phases: null });
      }),
    );
    renderPage("/workforce/board1/history", <WorkforceHistory />, "/workforce/:boardId/history");

    await waitFor(() => expect(versionsRead).toEqual(["7"]));
    expect(listed).toBe(false);
  });
});
