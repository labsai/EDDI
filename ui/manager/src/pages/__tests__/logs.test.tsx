import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, render, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, useLocation } from "react-router-dom";

function LocationProbe() {
  return <span data-testid="loc">{useLocation().search}</span>;
}
import { ThemeProvider } from "@/components/layout/theme-provider";
import { LogsPage } from "@/pages/logs";
import { useLogStream } from "@/hooks/use-logs";
import userEvent from "@testing-library/user-event";

// ─── Mocks ─────────────────────────────────────────────────────────────

vi.mock("@/hooks/use-logs", () => ({
  useLogStream: vi.fn().mockReturnValue({
    entries: [
      { timestamp: 1700000000000, level: "INFO", message: "Server started", loggerName: "main", agentId: "agent1", conversationId: "conv1" },
      { timestamp: 1700000001000, level: "ERROR", message: "NullPointerException\n  at com.example.Main.run(Main.java:42)\n  at com.example.App.start(App.java:10)\nCaused by: java.lang.RuntimeException", loggerName: "error-logger" },
      { timestamp: 1700000002000, level: "WARNING", message: "Low memory", loggerName: "sys" },
    ],
    sseConnected: true,
    exhausted: false,
    reconnect: vi.fn(),
    seeded: true,
    paused: false,
    setPaused: vi.fn(),
    clearEntries: vi.fn(),
  }),
  useHistoryLogs: vi.fn().mockReturnValue({
    data: [
      { timestamp: "2024-01-15T10:30:00Z", level: "INFO", message: "History log entry", agentId: "agent1", instanceId: "inst-abc" },
    ],
    isLoading: false,
    refetch: vi.fn()
  }),
  useInstanceId: vi.fn().mockReturnValue({ data: { instanceId: "test-instance-123" } }),
}));

vi.mock("@/hooks/use-chat", () => ({
  useDeployedAgents: vi.fn().mockReturnValue({
    data: [
      { id: "agent1", name: "Test Agent" },
    ]
  }),
}));

vi.mock("@/hooks/use-onboarding", () => ({
  useOnboarding: vi.fn().mockReturnValue(vi.fn()),
}));

vi.mock("@/lib/api/conversations", () => ({
  getConversationDescriptors: vi.fn().mockResolvedValue([]),
  parseConversationUri: vi.fn((uri: string) => uri.split("/").pop() ?? ""),
}));

// ─── Helpers ─────────────────────────────────────────────────────────────

let currentClient: QueryClient | null = null;

function logsTree(queryClient: QueryClient) {
  return (
    <MemoryRouter initialEntries={["/manage/logs"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <LogsPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function renderLogs() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  currentClient = queryClient;
  return render(logsTree(queryClient));
}

// ─── Tests ─────────────────────────────────────────────────────────────

describe("LogsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the logs-page container", () => {
    renderLogs();
    expect(screen.getByTestId("logs-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderLogs();
    expect(screen.getByText("Logs")).toBeInTheDocument();
  });

  it("renders Live and History tabs", () => {
    renderLogs();
    expect(screen.getByTestId("tab-live")).toBeInTheDocument();
    expect(screen.getByTestId("tab-history")).toBeInTheDocument();
  });

  it("shows stream badge for SSE connection", () => {
    renderLogs();
    expect(screen.getByTestId("stream-badge")).toBeInTheDocument();
  });

  it("renders agent filter input", () => {
    renderLogs();
    expect(screen.getByTestId("filter-agent")).toBeInTheDocument();
  });

  it("renders level filter dropdown", () => {
    renderLogs();
    expect(screen.getByTestId("filter-level")).toBeInTheDocument();
  });

  it("renders pause button", () => {
    renderLogs();
    expect(screen.getByTestId("pause-button")).toBeInTheDocument();
  });

  it("renders clear button", () => {
    renderLogs();
    expect(screen.getByTestId("clear-button")).toBeInTheDocument();
  });

  it("renders export logs button", () => {
    renderLogs();
    expect(screen.getByTestId("export-logs-btn")).toBeInTheDocument();
  });

  // ─── Interaction tests (mocked data) ────────────────────────────────────

  it("renders log entries when useLogStream returns data", () => {
    renderLogs();
    expect(screen.getByText("Server started")).toBeInTheDocument();
    expect(screen.getByText(/NullPointerException/)).toBeInTheDocument();
    expect(screen.getByText("Low memory")).toBeInTheDocument();
  });

  it("renders level badges for entries", () => {
    renderLogs();
    // Scope to the log entries scroll area to avoid matching <option> elements in filter dropdowns
    const logEntries = screen.getByTestId("logs-page");
    const badges = within(logEntries).getAllByText(/^(INFO|ERROR|WARN)$/);
    const badgeTexts = badges.map((b) => b.textContent);
    expect(badgeTexts).toContain("INFO");
    expect(badgeTexts).toContain("ERROR");
    expect(badgeTexts).toContain("WARN");
  });

  it("shows stacktrace toggle for error with stacktrace", () => {
    renderLogs();
    expect(screen.getByTestId("stacktrace-toggle")).toBeInTheDocument();
  });

  it("expands stacktrace when toggle is clicked", async () => {
    renderLogs();
    const user = userEvent.setup();
    const toggleBtn = screen.getByTestId("stacktrace-toggle");
    
    // Initially stacktrace is collapsed, so the frames won't be fully visible
    // Click toggle to expand
    await user.click(toggleBtn);
    
    // Verify stacktrace frame appears
    expect(screen.getByText(/at com.example.Main.run/)).toBeInTheDocument();
  });

  it("copies log entry to clipboard", async () => {
    const writeTextMock = vi.fn().mockResolvedValue(undefined);
    let clipboardSpy: ReturnType<typeof vi.spyOn> | undefined;
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true
      });
    } else {
      clipboardSpy = vi.spyOn(navigator.clipboard, 'writeText').mockImplementation(writeTextMock as never) as unknown as ReturnType<typeof vi.spyOn>;
    }

    renderLogs();
    const user = userEvent.setup();
    
    // There are multiple copy buttons, pick the first one
    const copyBtns = screen.getAllByTestId("copy-log-btn");
    await user.click(copyBtns[0]!);
    
    expect(writeTextMock).toHaveBeenCalled();

    // Restore clipboard spy to avoid leaking into other tests
    clipboardSpy?.mockRestore();
  });

  it("switches to History tab and shows history content", async () => {
    renderLogs();
    const user = userEvent.setup();
    
    await user.click(screen.getByTestId("tab-history"));
    
    await waitFor(() => {
      // History entry from mock
      expect(screen.getByText("History log entry")).toBeInTheDocument();
    });
  });

  it("shows level stats bar", () => {
    renderLogs();
    // The stats bar appears when there are errors/warnings
    expect(screen.getByTestId("level-stats")).toBeInTheDocument();
  });

  it("filters entries by text search", async () => {
    renderLogs();
    const user = userEvent.setup();
    
    const searchInput = screen.getByTestId("text-search");
    await user.type(searchInput, "Server");
    
    // "Server started" should still be visible
    expect(screen.getByText("Server started")).toBeInTheDocument();
    // "Low memory" should be filtered out
    expect(screen.queryByText("Low memory")).not.toBeInTheDocument();
  });

  it("shows instance badge", () => {
    renderLogs();
    expect(screen.getByText("test-instance-123")).toBeInTheDocument();
  });

  it("renders agent filter with options", async () => {
    renderLogs();
    const user = userEvent.setup();
    
    // The filter is a generic component, click to open it
    const agentFilterBtn = screen.getByTestId("filter-agent");
    await user.click(agentFilterBtn);
    
    await waitFor(() => {
      expect(screen.getByText("Test Agent")).toBeInTheDocument();
    });
  });

  it("shows loading state when not yet seeded", () => {
    vi.mocked(useLogStream).mockReturnValueOnce({ entries: [], sseConnected: true, seeded: false, exhausted: false, reconnect: vi.fn(), paused: false, setPaused: vi.fn(), clearEntries: vi.fn() });
    renderLogs();
    expect(screen.getByText("Loading recent logs…")).toBeInTheDocument();
  });

  it("shows no-activity state when seeded but empty", () => {
    vi.mocked(useLogStream).mockReturnValueOnce({ entries: [], sseConnected: true, seeded: true, exhausted: false, reconnect: vi.fn(), paused: false, setPaused: vi.fn(), clearEntries: vi.fn() });
    renderLogs();
    expect(screen.getByText("No recent log activity.")).toBeInTheDocument();
  });

  it("shows connecting state when not connected", () => {
    vi.mocked(useLogStream).mockReturnValueOnce({ entries: [], sseConnected: false, seeded: true, exhausted: false, reconnect: vi.fn(), paused: false, setPaused: vi.fn(), clearEntries: vi.fn() });
    renderLogs();
    expect(screen.getByText("Connecting to stream...")).toBeInTheDocument();
  });

  it("shows a terminal Disconnected state with a Reconnect button once retries are spent", async () => {
    const reconnect = vi.fn();
    const base = vi.mocked(useLogStream)();
    vi.mocked(useLogStream).mockReturnValue({
      ...base,
      entries: [],
      sseConnected: false,
      exhausted: true,
      reconnect,
    });
    try {
      renderLogs();
      expect(screen.queryByText("Connecting to stream...")).not.toBeInTheDocument();
      expect(screen.getByText("Disconnected from the log stream.")).toBeInTheDocument();
      expect(screen.getByTestId("stream-badge")).toHaveTextContent("Disconnected");
      await userEvent.setup().click(screen.getByTestId("reconnect-button"));
      expect(reconnect).toHaveBeenCalledTimes(1);
    } finally {
      vi.mocked(useLogStream).mockReturnValue(base);
    }
  });

  it("says no loaded entries match when a text search finds nothing", async () => {
    renderLogs();
    await userEvent.setup().type(screen.getByTestId("text-search"), "zzz-nothing");
    expect(screen.getByTestId("live-no-matches")).toBeInTheDocument();
  });

  it("keeps History filters in the URL when switching tabs", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/manage/logs?tab=history&level=ERROR&agent=agent1"]}>
        <QueryClientProvider client={new QueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <LogsPage />
            <LocationProbe />
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("tab-live"));
    expect(screen.getByTestId("loc").textContent).toBe("?level=ERROR&agent=agent1");
    await user.click(screen.getByTestId("tab-history"));
    expect(screen.getByTestId("loc").textContent).toContain("tab=history");
    expect(screen.getByTestId("loc").textContent).toContain("level=ERROR");
  });

  it("counts errors and warnings with proper plurals", () => {
    renderLogs();
    // fixture: one ERROR, one WARNING
    const stats = screen.getByTestId("level-stats");
    expect(within(stats).getByText("1 error")).toBeInTheDocument();
    expect(within(stats).getByText("1 warning")).toBeInTheDocument();
  });

  it("history rows carry the date and an ISO timestamp title", async () => {
    renderLogs();
    await userEvent.setup().click(screen.getByTestId("tab-history"));
    const row = (await screen.findByText("History log entry")).closest(".group")!;
    const stamp = row.querySelector("span[title]")!;
    expect(stamp.getAttribute("title")).toMatch(/^2024-01-15T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}$/);
    expect(stamp.textContent).toMatch(/2024/);
  });

  // Rows used to be keyed by timestamp + list index. Every new line shifts
  // every index, so React remounted the whole list once per log line — which,
  // among other things, collapsed any stack trace the operator had opened.
  it("keeps an expanded stack trace open when a new line arrives", async () => {
    const base = vi.mocked(useLogStream)();
    const user = userEvent.setup();
    const { rerender } = renderLogs();

    await user.click(screen.getByTestId("stacktrace-toggle"));
    expect(screen.getByText("Hide stacktrace")).toBeInTheDocument();

    vi.mocked(useLogStream).mockReturnValue({
      ...base,
      entries: [
        { timestamp: 1700000003000, level: "INFO", message: "A newer line", loggerName: "main" },
        ...base.entries,
      ],
    });
    rerender(logsTree(currentClient!));

    expect(screen.getByText("A newer line")).toBeInTheDocument();
    expect(screen.getByText("Hide stacktrace")).toBeInTheDocument();
    vi.mocked(useLogStream).mockReturnValue(base);
  });
});

