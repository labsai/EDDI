import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";
import type { Agent } from "@/lib/api/agents";
import { server } from "@/test/mocks/server";

vi.mock("sonner", async () => {
  const actual = await vi.importActual<typeof import("sonner")>("sonner");
  return { ...actual, toast: { ...actual.toast, error: vi.fn(), success: vi.fn() } };
});
import { toast } from "sonner";

/**
 * Page-level recovery from a conflicting section save.
 *
 * A section save addressed to a version another client has already superseded
 * answers 409. The page follows the latest version only by re-reading its
 * version list (`useAgentVersions()[0]`), and a failed save refetches nothing
 * by itself — so without the conflict path in `useAgentSectionSave` (drop the
 * chain, invalidate the agent queries) the page stayed on the superseded
 * version and every later save repeated the 409.
 */

let seq = 0;
let agentId: string;
let current: number;
let docs: Record<number, Agent>;
let puts: number[];

beforeEach(() => {
  vi.mocked(toast.error).mockReset();
  // Section save state lives at module scope per agent id, so each test uses its own agent.
  agentId = `conflict-agent-${++seq}`;
  current = 1;
  docs = { 1: { workflows: [], description: "original" } };
  puts = [];
  const versionOf = (request: Request) => Number(new URL(request.url).searchParams.get("version") ?? "1");

  server.use(
    // Before the `:id` handler below, which would otherwise match "descriptors".
    http.get("*/agentstore/agents/descriptors", ({ request }) => {
      const url = new URL(request.url);
      if (url.searchParams.get("filter") !== agentId) return;
      const v = versionOf(request);
      if (v > current) return HttpResponse.json([]);
      return HttpResponse.json([
        {
          resource: `eddi://ai.labs.agent/agentstore/agents/${agentId}?version=${v}`,
          name: "Conflict Agent",
          description: "",
          lastModifiedOn: 1_700_000_000_000 + v,
        },
      ]);
    }),
    http.get("*/agentstore/agents/:id/currentversion", ({ params }) => {
      if (params.id !== agentId) return;
      return HttpResponse.json(current);
    }),
    http.get("*/agentstore/agents/:id", ({ request, params }) => {
      if (params.id !== agentId) return;
      const doc = docs[versionOf(request)];
      return doc ? HttpResponse.json(doc) : new HttpResponse(null, { status: 404 });
    }),
    http.put("*/agentstore/agents/:id", async ({ request, params }) => {
      if (params.id !== agentId) return;
      const v = versionOf(request);
      puts.push(v);
      if (v !== current) return HttpResponse.json({ message: "Conflict" }, { status: 409 });
      current += 1;
      docs[current] = (await request.json()) as Agent;
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.agent/agentstore/agents/${agentId}?version=${current}` },
      });
    }),
  );
});

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter initialEntries={[`/manage/agentview/${agentId}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-version-conflict">
          <Routes>
            <Route path="/manage/agentview/:id" element={<AgentDetailPage />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function snapshotToggle() {
  return screen.getByTestId("auto-snapshot-enabled") as HTMLInputElement;
}

describe("Agent detail — a section save that conflicts", () => {
  it("moves onto the newer version after a 409, and the next section save goes there", async () => {
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => expect(screen.getByTestId("version-badge")).toHaveTextContent("v1"));
    await user.click(screen.getByText(/Session Management/i));
    await waitFor(() => expect(snapshotToggle()).not.toBeDisabled());

    // 1. A save that succeeds: v1 -> v2, and the page follows it.
    await user.click(snapshotToggle());
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("2"));
    await waitFor(() => expect(snapshotToggle().checked).toBe(true));
    await waitFor(() => expect(snapshotToggle()).not.toBeDisabled());

    // 2. Another client writes v3. The page has no way to know yet.
    current = 3;
    docs[3] = { ...docs[2]!, description: "edited elsewhere" };

    // 3. The next section save is addressed to v2 (the chain the first save
    //    left) and conflicts. The page must refetch and select v3, the first
    //    entry of the refetched version list.
    await user.click(snapshotToggle());
    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("3"));
    expect(puts).toEqual([1, 2]);

    // 4. The next section save goes to v3 and lands, keeping the other client's edit.
    await waitFor(() => expect(snapshotToggle()).not.toBeDisabled());
    await waitFor(() => expect(snapshotToggle().checked).toBe(true));
    await user.click(snapshotToggle());
    await waitFor(() => expect(current).toBe(4));
    expect(puts).toEqual([1, 2, 3]);
    expect(docs[4]).toMatchObject({
      description: "edited elsewhere",
      sessionManagement: { autoSnapshot: { enabled: false } },
    });
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("4"));
    expect(toast.error).toHaveBeenCalledTimes(1);
  });
});
