import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { type ReactNode } from "react";
import { server } from "@/test/mocks/server";
import { useWorkforceAnalytics } from "@/hooks/use-workforce-analytics";

/**
 * The Insights page is driven entirely by this hook, and none of its aggregation
 * was exercised. Everything here is arithmetic over conversation transcripts, so
 * the tests assert the numbers rather than that it renders.
 *
 * Driven through MSW at the real endpoints (`/groupstore/groups/descriptors`,
 * `/groupstore/groups/:id`, `/groups/:id/conversations`, `/agentstore/...`) so
 * the enrichment and per-group fan-out are covered too, not just the reducer.
 */

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

/** `created` today, so it lands inside the 30-day activity window. */
const today = new Date();
const iso = (offsetMinutes = 0) =>
  new Date(today.getTime() + offsetMinutes * 60_000).toISOString();
const todayKey = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;

interface Entry {
  type: string;
  speakerAgentId?: string;
  speakerDisplayName?: string;
  content?: string;
}

function conversation(
  id: string,
  groupId: string,
  state: string,
  transcript: Entry[],
  createdOffset = 0,
  durationMin = 10,
) {
  return {
    id,
    groupId,
    state,
    created: iso(createdOffset),
    lastModified: iso(createdOffset + durationMin),
    question: `question for ${id}`,
    transcript,
  };
}

/** Two groups with distinct styles and member counts. */
function mockBackend(convsByGroup: Record<string, unknown[]>, styles: Record<string, string>) {
  server.use(
    http.get("*/groupstore/groups/descriptors", () =>
      HttpResponse.json(
        Object.keys(styles).map((id) => ({
          resource: `eddi://ai.labs.group/groupstore/groups/${id}?version=1`,
          name: `Group ${id}`,
          description: "",
          createdOn: 0,
          lastModifiedOn: 0,
        })),
      ),
    ),
    http.get("*/groupstore/groups/:id", ({ params }) => {
      const id = String(params.id);
      return HttpResponse.json({
        name: `Group ${id}`,
        description: "",
        style: styles[id],
        members: [{ agentId: "a1", displayName: "Ana" }, { agentId: "a2", displayName: "Bo" }],
      });
    }),
    http.get("*/groups/:id/conversations", ({ params }) =>
      HttpResponse.json(convsByGroup[String(params.id)] ?? []),
    ),
    http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([])),
  );
}

async function analytics(filters?: Parameters<typeof useWorkforceAnalytics>[0]) {
  const { result } = renderHook(() => useWorkforceAnalytics(filters), {
    wrapper: createWrapper(),
  });
  await waitFor(() => expect(result.current.isLoading).toBe(false), { timeout: 8000 });
  return result;
}

beforeEach(() => {
  server.resetHandlers();
});

describe("useWorkforceAnalytics", () => {
  it("reports zeroed KPIs and no error when there are no groups", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => HttpResponse.json([])),
      http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([])),
    );
    const result = await analytics();

    expect(result.current.totalDiscussions).toBe(0);
    expect(result.current.completionRate).toBe(0);
    expect(result.current.groupCount).toBe(0);
    expect(result.current.hasError).toBe(false);
    // The 30-day window is always fully populated so the heatmap has no holes.
    expect(result.current.dailyActivity).toHaveLength(30);
  });

  it("computes completion rate, averages and team size across groups", async () => {
    mockBackend(
      {
        g1: [
          conversation("c1", "g1", "COMPLETED", [], 0, 10),
          conversation("c2", "g1", "FAILED", [], 0, 30),
        ],
        g2: [conversation("c3", "g2", "COMPLETED", [], 0, 20)],
      },
      { g1: "ROUND_TABLE", g2: "DEBATE" },
    );
    const result = await analytics();

    expect(result.current.totalDiscussions).toBe(3);
    expect(result.current.unfilteredTotal).toBe(3);
    // 2 of 3 COMPLETED
    expect(result.current.completionRate).toBe(67);
    // (10 + 30 + 20) / 3 minutes, in ms
    expect(result.current.avgDurationMs).toBe(20 * 60_000);
    // Both groups have 2 members
    expect(result.current.avgTeamSize).toBe(2);
    expect(result.current.groupCount).toBe(2);
    expect(result.current.isFiltered).toBe(false);
  });

  it("aggregates per-agent contributions, sessions and errors", async () => {
    mockBackend(
      {
        g1: [
          conversation("c1", "g1", "COMPLETED", [
            { type: "QUESTION", speakerAgentId: "user", speakerDisplayName: "You", content: "hi" },
            { type: "OPINION", speakerAgentId: "a1", speakerDisplayName: "Ana", content: "12345" },
            { type: "OPINION", speakerAgentId: "a1", speakerDisplayName: "Ana", content: "123" },
            { type: "ERROR", speakerAgentId: "a2", speakerDisplayName: "Bo" },
          ]),
          conversation("c2", "g1", "COMPLETED", [
            { type: "OPINION", speakerAgentId: "a1", speakerDisplayName: "Ana", content: "1" },
          ]),
        ],
      },
      { g1: "ROUND_TABLE" },
    );
    const result = await analytics();

    const ana = result.current.agentStats.find((a) => a.agentId === "a1")!;
    expect(ana.contributions).toBe(3);
    // Content length is summed only over contributing entries.
    expect(ana.totalContentLength).toBe(5 + 3 + 1);
    // Two distinct conversations, counted once each however many turns taken.
    expect(ana.sessions).toBe(2);
    expect(ana.errors).toBe(0);

    const bo = result.current.agentStats.find((a) => a.agentId === "a2")!;
    // ERROR entries count as errors and never as contributions.
    expect(bo.errors).toBe(1);
    expect(bo.contributions).toBe(0);

    // Sorted by contributions, descending.
    expect(result.current.agentStats[0]!.agentId).toBe("a1");
  });

  it("counts the human asker as an active expert (known inflation)", async () => {
    // Documented, not endorsed: every transcript entry with a speakerAgentId
    // feeds activeExperts, including the QUESTION entry whose speaker is the
    // human. Pinned so the count changing is a deliberate decision — the
    // leaderboard shows a "You" row for the same reason.
    mockBackend(
      {
        g1: [
          conversation("c1", "g1", "COMPLETED", [
            { type: "QUESTION", speakerAgentId: "user", speakerDisplayName: "You" },
            { type: "OPINION", speakerAgentId: "a1", speakerDisplayName: "Ana", content: "x" },
          ]),
        ],
      },
      { g1: "ROUND_TABLE" },
    );
    const result = await analytics();

    expect(result.current.activeExperts).toBe(2);
    expect(result.current.agentStats.map((a) => a.agentId).sort()).toEqual(["a1", "user"]);
  });

  it("filters by outcome while keeping unfiltered totals for the dropdowns", async () => {
    mockBackend(
      {
        g1: [
          conversation("c1", "g1", "COMPLETED", []),
          conversation("c2", "g1", "FAILED", []),
          conversation("c3", "g1", "FAILED", []),
        ],
      },
      { g1: "ROUND_TABLE" },
    );
    const result = await analytics({ outcome: "FAILED" });

    expect(result.current.totalDiscussions).toBe(2);
    expect(result.current.unfilteredTotal).toBe(3);
    expect(result.current.isFiltered).toBe(true);
    // Dropdown counts must stay unfiltered, or selecting one filter would empty
    // the others and the user could not switch between them.
    expect(result.current.outcomeCounts).toEqual({ COMPLETED: 1, FAILED: 2 });
  });

  it("filters by style", async () => {
    mockBackend(
      {
        g1: [conversation("c1", "g1", "COMPLETED", [])],
        g2: [
          conversation("c2", "g2", "COMPLETED", []),
          conversation("c3", "g2", "COMPLETED", []),
        ],
      },
      { g1: "ROUND_TABLE", g2: "DEBATE" },
    );
    const result = await analytics({ style: "DEBATE" });

    expect(result.current.totalDiscussions).toBe(2);
    expect(result.current.styleCounts).toEqual({ ROUND_TABLE: 1, DEBATE: 2 });
    // Style distribution counts GROUPS in the filtered set, not conversations.
    expect(result.current.styleDistribution).toEqual([{ style: "DEBATE", count: 1 }]);
  });

  it("filters by day and records it in the daily activity series", async () => {
    mockBackend(
      { g1: [conversation("c1", "g1", "COMPLETED", []), conversation("c2", "g1", "COMPLETED", [])] },
      { g1: "ROUND_TABLE" },
    );
    const result = await analytics({ date: todayKey });

    expect(result.current.totalDiscussions).toBe(2);
    const todayEntry = result.current.dailyActivity.find((d) => d.date === todayKey);
    expect(todayEntry?.count).toBe(2);
  });

  it("builds outcome, phase and recent-discussion views", async () => {
    mockBackend(
      {
        g1: [
          conversation(
            "c1",
            "g1",
            "COMPLETED",
            [
              { type: "QUESTION", speakerAgentId: "user" },
              { type: "OPINION", speakerAgentId: "a1", content: "x" },
              { type: "OPINION", speakerAgentId: "a2", content: "y" },
            ],
            -60,
          ),
          conversation("c2", "g1", "FAILED", [{ type: "ERROR", speakerAgentId: "a1" }], 0),
        ],
      },
      { g1: "ROUND_TABLE" },
    );
    const result = await analytics();

    expect(result.current.outcomeDistribution).toEqual(
      expect.arrayContaining([
        { state: "COMPLETED", count: 1 },
        { state: "FAILED", count: 1 },
      ]),
    );
    // Sorted by count descending, so OPINION (2) leads.
    expect(result.current.phaseDistribution[0]).toEqual({ type: "OPINION", count: 2 });

    // Newest first, and enriched with the group's name and member count.
    expect(result.current.recentDiscussions[0]!.id).toBe("c2");
    expect(result.current.recentDiscussions[0]!.groupName).toBe("Group g1");
    expect(result.current.recentDiscussions[0]!.memberCount).toBe(2);
  });

  it("surfaces hasError when a group's conversations fail to load", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.group/groupstore/groups/g1?version=1",
            name: "Group g1",
            description: "",
            createdOn: 0,
            lastModifiedOn: 0,
          },
        ]),
      ),
      http.get("*/groupstore/groups/:id", () =>
        HttpResponse.json({ name: "Group g1", style: "ROUND_TABLE", members: [] }),
      ),
      http.get("*/groups/:id/conversations", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
      http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([])),
    );
    const result = await analytics();

    // The page shows its error state off this flag; silently reporting zero
    // discussions would read as "nothing has happened yet".
    expect(result.current.hasError).toBe(true);
  });
});
