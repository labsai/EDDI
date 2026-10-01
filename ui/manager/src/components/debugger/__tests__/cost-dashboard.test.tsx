import { beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CostDashboard } from "@/components/debugger/cost-dashboard";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { formatUsd } from "@/lib/utils";

function renderDashboard(conversationId: string | null = "conv1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-cost">
          <CostDashboard conversationId={conversationId} isActive />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("CostDashboard", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  // ── Empty / null states ────────────────────────────────────────────
  it("renders empty state when conversationId is null", () => {
    renderDashboard(null);
    expect(screen.getByText(/Send a message to see cost metrics/i)).toBeInTheDocument();
  });

  it("renders empty state when conversationId is empty string", () => {
    renderDashboard("");
    expect(screen.getByText(/Send a message to see cost metrics/i)).toBeInTheDocument();
  });

  // ── Stat cards ─────────────────────────────────────────────────────
  it("renders cost-dashboard container with data-testid", async () => {
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    });
  });

  it("displays Total Cost stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Total Cost/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays Total Tokens stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Total Tokens/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays Turns stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Turns/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays Avg Latency stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Avg Latency/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  // ── Token distribution ─────────────────────────────────────────────
  it("displays token distribution bar with input/output", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Input/i)).toBeInTheDocument();
        expect(within(dashboard).getByText(/Output/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  // ── Dollar cost values ─────────────────────────────────────────────
  it("displays dollar-sign cost values", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        const costElements = within(dashboard).getAllByText(/\$/);
        expect(costElements.length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  // ── Per-turn table ─────────────────────────────────────────────────
  it("displays per-turn table with model name", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getAllByText(/5\.4-mini/i).length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  it("displays token counts in per-turn table", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        const tokenLabels = within(dashboard).getAllByText(/Tokens/i);
        expect(tokenLabels.length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  // ── Error state ────────────────────────────────────────────────────
  it("shows error state when both APIs fail", async () => {
    server.use(
      http.get("*/llm/tools/costs/conversation/*", () => {
        return new HttpResponse(null, { status: 500 });
      }),
      http.get("*/auditstore/*", () => {
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderDashboard("conv-err");
    await waitFor(
      () => {
        expect(screen.getByTestId("cost-dashboard-error")).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  // The dashboard read ONE page of 200 entries. The backend returns them newest
  // first, so on a longer conversation "Total Cost" summed only recent turns.
  it("sums the whole audit trail across pages, not just the newest page", async () => {
    const skips: number[] = [];
    const row = (i: number) => ({
      id: `e${i}`, conversationId: "conv-long", agentId: "a", agentVersion: 1, userId: null,
      environment: "production", stepIndex: i, taskId: "llm", taskType: "ai.labs.llm",
      taskIndex: 0, durationMs: 10, input: null, output: null, llmDetail: null,
      toolCalls: null, actions: null, cost: 0.01, timestamp: new Date(i * 1000).toISOString(),
      hmac: null, agentSignature: null,
    });
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        const url = new URL(request.url);
        const skip = Number(url.searchParams.get("skip") ?? 0);
        const limit = Number(url.searchParams.get("limit") ?? 100);
        skips.push(skip);
        const total = 650;
        const rows = [];
        for (let i = skip; i < Math.min(skip + limit, total); i++) rows.push(row(i));
        return HttpResponse.json(rows);
      })
    );

    renderDashboard("conv-long");
    const dashboard = await screen.findByTestId("cost-dashboard");
    // 650 entries at $0.01 — one page of 500 would have said $5.00.
    await waitFor(() =>
      expect(within(dashboard).getAllByText(formatUsd(6.5)).length).toBeGreaterThan(0)
    );
    expect(within(dashboard).queryByText(formatUsd(5))).not.toBeInTheDocument();
    expect(skips).toEqual([0, 500]);
    expect(screen.queryByTestId("cost-dashboard-partial")).not.toBeInTheDocument();
  });

  it("says so when the conversation is longer than the debugger will load", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        const limit = Number(new URL(request.url).searchParams.get("limit") ?? 100);
        // Always a full page: the walk only stops at its page cap.
        return HttpResponse.json(
          Array.from({ length: limit }, (_, i) => ({
            id: `p${i}`, conversationId: "conv-huge", agentId: "a", agentVersion: 1,
            userId: null, environment: "production", stepIndex: i, taskId: "llm",
            taskType: "ai.labs.llm", taskIndex: 0, durationMs: 1, input: null, output: null,
            llmDetail: null, toolCalls: null, actions: null, cost: 0,
            timestamp: new Date(0).toISOString(), hmac: null, agentSignature: null,
          }))
        );
      })
    );
    renderDashboard("conv-huge");
    expect(await screen.findByTestId("cost-dashboard-partial", {}, { timeout: 5000 })).toBeInTheDocument();
  });
});

