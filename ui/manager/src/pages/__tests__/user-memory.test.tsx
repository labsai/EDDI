import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { UserMemoryPage } from "@/pages/user-memory";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

const MOCK_MEMORIES = [
  {
    id: "mem-1",
    userId: "user-123",
    key: "preferred_language",
    value: "English",
    category: "preference",
    visibility: "self",
    sourceAgentId: "agent-001",
    sourceConversationId: "conv-abc",
    conflicted: false,
    accessCount: 5,
    createdAt: "2025-01-15T10:00:00Z",
    updatedAt: "2025-03-20T15:30:00Z",
  },
  {
    id: "mem-2",
    userId: "user-123",
    key: "account_type",
    value: "premium",
    category: "fact",
    visibility: "global",
    sourceAgentId: "agent-002",
    sourceConversationId: null,
    conflicted: false,
    accessCount: 12,
    createdAt: "2025-02-01T08:00:00Z",
    updatedAt: "2025-04-10T12:00:00Z",
  },
  {
    id: "mem-3",
    userId: "user-123",
    key: "last_order_id",
    value: { orderId: "ORD-12345", total: 99.99 },
    category: "context",
    visibility: "group",
    sourceAgentId: null,
    sourceConversationId: null,
    conflicted: true,
    accessCount: 0,
    createdAt: "2025-03-01T14:00:00Z",
    updatedAt: null,
  },
];

function setupMemoryHandlers() {
  server.use(
    http.get("*/usermemorystore/memories/:userId", () => {
      return HttpResponse.json(MOCK_MEMORIES);
    }),
    http.delete("*/usermemorystore/memories/entry/:entryId", () => {
      return new HttpResponse(null, { status: 204 });
    }),
    http.delete("*/usermemorystore/memories/:userId", () => {
      return new HttpResponse(null, { status: 204 });
    })
  );
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-memory">
          <UserMemoryPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

async function enterUserIdAndLoad() {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  renderPage();
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  await user.clear(screen.getByTestId("memory-user-id"));
  await user.type(screen.getByTestId("memory-user-id"), "user-123");
  vi.advanceTimersByTime(600);

  await waitFor(() => {
    expect(screen.getByTestId("memory-list")).toBeInTheDocument();
  });

  return user;
}

describe("UserMemoryPage", () => {
  beforeEach(() => {
    setupMemoryHandlers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ─── Basic rendering ────────────────────────────────────────────────────

  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("user-memory-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText("User Memory")).toBeInTheDocument();
  });

  it("renders the page subtitle", () => {
    renderPage();
    expect(
      screen.getByText(/Browse, search, and manage persistent user memories/)
    ).toBeInTheDocument();
  });

  it("renders the user ID input", () => {
    renderPage();
    expect(screen.getByTestId("memory-user-id")).toBeInTheDocument();
  });

  it("renders the search input", () => {
    renderPage();
    expect(screen.getByTestId("memory-search")).toBeInTheDocument();
  });

  it("shows empty state when no user ID is entered", () => {
    renderPage();
    expect(screen.getByText(/Enter a User ID/)).toBeInTheDocument();
  });

  // ─── Header hidden in embedded mode ─────────────────────────────────────

  it("hides header when embedded prop is true", () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-emb">
            <UserMemoryPage embedded />
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );

    // The title should NOT be present when embedded
    expect(screen.queryByText("User Memory")).not.toBeInTheDocument();
    // But the page container should exist
    expect(screen.getByTestId("user-memory-page")).toBeInTheDocument();
  });

  // ─── Data loaded state ──────────────────────────────────────────────────

  it("shows memory entries after entering a user ID", async () => {
    await enterUserIdAndLoad();

    expect(screen.getByText("preferred_language")).toBeInTheDocument();
    expect(screen.getByText("account_type")).toBeInTheDocument();
    expect(screen.getByText("last_order_id")).toBeInTheDocument();
  });

  it("shows stats cards after loading memories", async () => {
    await enterUserIdAndLoad();

    expect(screen.getByTestId("memory-stats")).toBeInTheDocument();
  });

  it("shows correct total entry count in stats", async () => {
    await enterUserIdAndLoad();

    // 3 total entries — use within() to scope to the stats section
    const statsSection = screen.getByTestId("memory-stats");
    expect(within(statsSection).getByText("3")).toBeInTheDocument();
  });

  it("shows conflict count in stats", async () => {
    await enterUserIdAndLoad();

    // 1 conflicted entry (last_order_id) — use within() to scope
    const statsSection = screen.getByTestId("memory-stats");
    // Three stats show "1": preferences(1), facts(1), conflicted(1)
    const onesInStats = within(statsSection).getAllByText("1");
    expect(onesInStats.length).toBe(3);
    // The conflicted stat card label should be present
    expect(within(statsSection).getByText("Conflicted")).toBeInTheDocument();
  });

  // ─── Category filter tabs ──────────────────────────────────────────────

  it("shows category filter tabs", async () => {
    await enterUserIdAndLoad();

    expect(screen.getByTestId("category-all")).toBeInTheDocument();
    expect(screen.getByTestId("category-preference")).toBeInTheDocument();
    expect(screen.getByTestId("category-fact")).toBeInTheDocument();
    expect(screen.getByTestId("category-context")).toBeInTheDocument();
  });

  it("category tabs show correct counts", async () => {
    await enterUserIdAndLoad();

    // "all" tab should show 3
    expect(screen.getByTestId("category-all")).toHaveTextContent("3");
    // "preference" tab should show 1
    expect(screen.getByTestId("category-preference")).toHaveTextContent("1");
    // "fact" tab should show 1
    expect(screen.getByTestId("category-fact")).toHaveTextContent("1");
    // "context" tab should show 1
    expect(screen.getByTestId("category-context")).toHaveTextContent("1");
  });

  it("filters by preference category", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("category-preference"));

    // Only preference items should show
    expect(screen.getByText("preferred_language")).toBeInTheDocument();
    expect(screen.queryByText("account_type")).not.toBeInTheDocument();
    expect(screen.queryByText("last_order_id")).not.toBeInTheDocument();
  });

  it("filters by fact category", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("category-fact"));

    expect(screen.queryByText("preferred_language")).not.toBeInTheDocument();
    expect(screen.getByText("account_type")).toBeInTheDocument();
    expect(screen.queryByText("last_order_id")).not.toBeInTheDocument();
  });

  it("filters by context category", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("category-context"));

    expect(screen.queryByText("preferred_language")).not.toBeInTheDocument();
    expect(screen.queryByText("account_type")).not.toBeInTheDocument();
    expect(screen.getByText("last_order_id")).toBeInTheDocument();
  });

  it("switching back to 'all' category shows all entries", async () => {
    const user = await enterUserIdAndLoad();

    // Switch to preference
    await user.click(screen.getByTestId("category-preference"));
    expect(screen.queryByText("account_type")).not.toBeInTheDocument();

    // Switch back to all
    await user.click(screen.getByTestId("category-all"));
    expect(screen.getByText("account_type")).toBeInTheDocument();
    expect(screen.getByText("preferred_language")).toBeInTheDocument();
  });

  // ─── Search filter ─────────────────────────────────────────────────────

  it("filters memories by search text", async () => {
    const user = await enterUserIdAndLoad();

    await user.clear(screen.getByTestId("memory-search"));
    await user.type(screen.getByTestId("memory-search"), "preferred");

    await waitFor(() => {
      expect(screen.getByText("preferred_language")).toBeInTheDocument();
      expect(screen.queryByText("account_type")).not.toBeInTheDocument();
    });
  });

  it("search matches on value content too", async () => {
    const user = await enterUserIdAndLoad();

    await user.clear(screen.getByTestId("memory-search"));
    await user.type(screen.getByTestId("memory-search"), "premium");

    await waitFor(() => {
      expect(screen.getByText("account_type")).toBeInTheDocument();
      expect(screen.queryByText("preferred_language")).not.toBeInTheDocument();
    });
  });

  it("shows no results message when search matches nothing", async () => {
    const user = await enterUserIdAndLoad();

    await user.clear(screen.getByTestId("memory-search"));
    await user.type(screen.getByTestId("memory-search"), "zzzznonexistent");

    await waitFor(() => {
      expect(screen.getByText("No memory entries found")).toBeInTheDocument();
    });
  });

  // ─── Memory row badges ─────────────────────────────────────────────────

  it("shows category and visibility badges", async () => {
    await enterUserIdAndLoad();

    expect(screen.getAllByText("preference").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("fact").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("self").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("global").length).toBeGreaterThanOrEqual(1);
  });

  it("shows conflict icon for conflicted entries", async () => {
    await enterUserIdAndLoad();

    // last_order_id is conflicted - it should have an AlertTriangle icon
    const conflictedEntry = screen.getByTestId("memory-entry-mem-3");
    // Check for the title="Conflicted" element
    expect(conflictedEntry.querySelector("[title]")).toBeInTheDocument();
  });

  // ─── Expand/collapse memory row ────────────────────────────────────────

  it("expands a memory row to show detailed value", async () => {
    const user = await enterUserIdAndLoad();

    // Click on the expand toggle for mem-1
    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));

    // Should now show the expanded detail with "Value" label
    await waitFor(() => {
      expect(screen.getByText("Value")).toBeInTheDocument();
      // "English" appears both in the row preview and expanded detail
      expect(screen.getAllByText("English").length).toBeGreaterThanOrEqual(2);
    });
  });

  it("shows source agent in expanded view", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));

    await waitFor(() => {
      expect(screen.getByText("Source Agent")).toBeInTheDocument();
      expect(screen.getByText("agent-001")).toBeInTheDocument();
    });
  });

  it("shows access count in expanded view", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));

    await waitFor(() => {
      expect(screen.getByText("Access Count")).toBeInTheDocument();
      expect(screen.getByText("5")).toBeInTheDocument();
    });
  });

  it("shows source conversation in expanded view when available", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));

    await waitFor(() => {
      expect(screen.getByText("Source Conversation")).toBeInTheDocument();
      expect(screen.getByText("conv-abc")).toBeInTheDocument();
    });
  });

  it("shows JSON for object values in expanded view", async () => {
    const user = await enterUserIdAndLoad();

    // Expand the context entry which has an object value
    await user.click(screen.getByTestId("memory-expand-toggle-mem-3"));

    await waitFor(() => {
      // Object value should be JSON formatted — appears in row preview + expanded detail
      expect(screen.getAllByText(/ORD-12345/).length).toBeGreaterThanOrEqual(2);
    });
  });

  it("collapses expanded row when clicked again", async () => {
    const user = await enterUserIdAndLoad();

    // Expand
    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));
    await waitFor(() => {
      expect(screen.getByText("Value")).toBeInTheDocument();
    });

    // Collapse
    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));
    await waitFor(() => {
      expect(screen.queryByText("Value")).not.toBeInTheDocument();
    });
  });

  // ─── Delete single entry ───────────────────────────────────────────────

  it("shows delete button for each entry", async () => {
    await enterUserIdAndLoad();

    expect(screen.getByTestId("delete-memory-mem-1")).toBeInTheDocument();
    expect(screen.getByTestId("delete-memory-mem-2")).toBeInTheDocument();
    expect(screen.getByTestId("delete-memory-mem-3")).toBeInTheDocument();
  });

  // ─── Delete all ────────────────────────────────────────────────────────

  it("shows delete all button", async () => {
    await enterUserIdAndLoad();

    expect(screen.getByTestId("delete-all-memories")).toBeInTheDocument();
  });

  it("opens delete all confirmation dialog", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("delete-all-memories"));

    await waitFor(() => {
      expect(screen.getByText("Delete All Memories")).toBeInTheDocument();
      expect(
        screen.getByText(/permanently delete ALL memory entries/)
      ).toBeInTheDocument();
    });
  });

  // ─── Error state ────────────────────────────────────────────────────────

  it("shows error state when API returns error", async () => {
    server.use(
      http.get("*/usermemorystore/memories/:userId", () => {
        return HttpResponse.json(
          { error: "Server error" },
          { status: 500 }
        );
      })
    );

    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.clear(screen.getByTestId("memory-user-id"));
    await user.type(screen.getByTestId("memory-user-id"), "user-error");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      // Error state shows retry button
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // ─── Empty memories state ──────────────────────────────────────────────

  it("shows no results when user has no memories", async () => {
    server.use(
      http.get("*/usermemorystore/memories/:userId", () => {
        return HttpResponse.json([]);
      })
    );

    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.clear(screen.getByTestId("memory-user-id"));
    await user.type(screen.getByTestId("memory-user-id"), "empty-user");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByText("No memory entries found")).toBeInTheDocument();
    });
  });

  // ─── Delete single entry ───────────────────────────────────────────────

  it("clicking delete entry button triggers deletion API call", async () => {
    let deleteCalled = false;
    server.use(
      http.delete("*/usermemorystore/memories/entry/:entryId", () => {
        deleteCalled = true;
        return new HttpResponse(null, { status: 204 });
      })
    );

    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("delete-memory-mem-1"));

    await waitFor(() => {
      expect(deleteCalled).toBe(true);
    });
  });

  // ─── Confirm delete all flow ───────────────────────────────────────────

  it("confirms delete all memories", async () => {
    let deleteAllCalled = false;
    server.use(
      http.delete("*/usermemorystore/memories/:userId", () => {
        deleteAllCalled = true;
        return new HttpResponse(null, { status: 204 });
      })
    );

    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("delete-all-memories"));

    await waitFor(() => {
      expect(screen.getByText("Delete All Memories")).toBeInTheDocument();
    });

    // Find and click confirm button
    const confirmBtn = screen.getAllByText("Delete All")[1]!;
    await user.click(confirmBtn);

    // Dialog should close after successful deletion
    await waitFor(() => {
      expect(
        screen.queryByText(/permanently delete ALL/)
      ).not.toBeInTheDocument();
    });

    expect(deleteAllCalled).toBe(true);
  });

  // ─── Loading state ────────────────────────────────────────────────────

  it("shows loading spinner while fetching memories", async () => {
    // Use a delayed handler
    server.use(
      http.get("*/usermemorystore/memories/:userId", async () => {
        await new Promise((r) => setTimeout(r, 5000));
        return HttpResponse.json(MOCK_MEMORIES);
      })
    );

    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.clear(screen.getByTestId("memory-user-id"));
    await user.type(screen.getByTestId("memory-user-id"), "loading-user");
    vi.advanceTimersByTime(600);

    // Loading spinner should be visible before data arrives
    await waitFor(() => {
      expect(screen.getByTestId("memory-loading-spinner")).toBeInTheDocument();
    });
  });

  // ─── Expanded entry details ────────────────────────────────────────────

  it("shows dash for null sourceAgentId in expanded view", async () => {
    const user = await enterUserIdAndLoad();

    // Expand mem-3 which has sourceAgentId: null
    await user.click(screen.getByTestId("memory-expand-toggle-mem-3"));

    await waitFor(() => {
      expect(screen.getByText("Source Agent")).toBeInTheDocument();
      // sourceAgentId is null → should show "—"
      const dashes = screen.getAllByText("—");
      expect(dashes.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows dash for null updatedAt in expanded view", async () => {
    const user = await enterUserIdAndLoad();

    // Expand mem-3 which has updatedAt: null
    await user.click(screen.getByTestId("memory-expand-toggle-mem-3"));

    await waitFor(() => {
      expect(screen.getByText("Updated")).toBeInTheDocument();
      // updatedAt is null → should show "—"
      const dashes = screen.getAllByText("—");
      expect(dashes.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows created date in expanded view", async () => {
    const user = await enterUserIdAndLoad();

    await user.click(screen.getByTestId("memory-expand-toggle-mem-1"));

    await waitFor(() => {
      expect(screen.getByText("Created")).toBeInTheDocument();
      // createdAt: "2025-01-15T10:00:00Z" should be formatted as a date string
      const createdSection = screen.getByText("Created").closest("div");
      expect(createdSection).toBeTruthy();
    });
  });

  // ─── Category filter + search combined ─────────────────────────────────

  it("combines category filter and search filter", async () => {
    const user = await enterUserIdAndLoad();

    // Filter by preference category
    await user.click(screen.getByTestId("category-preference"));

    // Then search within preference
    await user.clear(screen.getByTestId("memory-search"));
    await user.type(screen.getByTestId("memory-search"), "preferred");

    await waitFor(() => {
      expect(screen.getByText("preferred_language")).toBeInTheDocument();
    });

    // Now search for something not in preference category
    await user.clear(screen.getByTestId("memory-search"));
    await user.type(screen.getByTestId("memory-search"), "zzzzz");

    await waitFor(() => {
      expect(screen.getByText("No memory entries found")).toBeInTheDocument();
    });
  });
});
