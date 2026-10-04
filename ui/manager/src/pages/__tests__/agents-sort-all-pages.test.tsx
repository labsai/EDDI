import { afterAll, beforeAll, describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AgentsPage } from "@/pages/agents";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }));

// A full first page renders the infinite-scroll sentinel, which observes it.
beforeAll(() => {
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterAll(() => {
  vi.unstubAllGlobals();
});

/**
 * The list loads page by page (50 per page) and sorts what it holds. A name
 * sort over the first page alone put page two's "Alpha" agents nowhere near the
 * top — and said nothing about it.
 */
function agent(id: string, name: string, lastModifiedOn: number) {
  return {
    resource: `eddi://ai.labs.agent/agentstore/agents/${id}?version=1`,
    name,
    description: "",
    createdOn: 1,
    lastModifiedOn,
  };
}

describe("AgentsPage — sorting is over every agent, not only the loaded pages", () => {
  function twoPages() {
    const requestedPages: number[] = [];
    server.use(
      http.get("*/agentstore/agents/descriptors", ({ request }) => {
        const index = Number(new URL(request.url).searchParams.get("index") ?? "0");
        requestedPages.push(index);
        if (index === 0) {
          // A full page, newest first: "Zeta 0" … "Zeta 49".
          return HttpResponse.json(
            Array.from({ length: 50 }, (_, i) => agent(`z${i}`, `Zeta ${String(i).padStart(2, "0")}`, 1_000_000 - i)),
          );
        }
        return HttpResponse.json([agent("a1", "Alpha 1", 10), agent("a2", "Alpha 2", 9)]);
      }),
    );
    return requestedPages;
  }

  it("does not fetch more than the first page for the default order", async () => {
    const pages = twoPages();
    renderWithProviders(<AgentsPage />);
    await screen.findByTestId("agent-grid");
    await new Promise((r) => setTimeout(r, 100));
    expect(pages).toEqual([0]);
  });

  it("loads the remaining pages when the list is sorted by name", async () => {
    const pages = twoPages();
    renderWithProviders(<AgentsPage />);
    const user = userEvent.setup();
    await screen.findByTestId("agent-grid");
    await user.click(screen.getByTestId("view-toggle-list"));
    await screen.findByTestId("agent-list");

    await user.click(screen.getByLabelText("Sort by name"));

    await waitFor(() => expect(pages).toContain(1));
    // The Alpha agents from page two now lead the ascending order.
    await waitFor(() => {
      const rows = within(screen.getByTestId("agent-list")).getAllByRole("row").slice(1);
      expect(rows[0]!.querySelector("td")!.textContent).toContain("Alpha 1");
    });
  });
});
