import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { LogsPage } from "@/pages/logs";
import userEvent from "@testing-library/user-event";

// ─── Mocks ─────────────────────────────────────────────────────────────

vi.mock("@/hooks/use-logs", () => ({
  useLogStream: vi.fn().mockReturnValue({
    entries: [
      { timestamp: 1700000000000, level: "INFO", message: "Server started", loggerName: "main", agentId: "agent1", conversationId: "conv1" },
      { timestamp: 1700000001000, level: "ERROR", message: "NullPointerException\n  at com.example.Main.run(Main.java:42)\n  at com.example.App.start(App.java:10)\nCaused by: java.lang.RuntimeException", loggerName: "error-logger" },
      { timestamp: 1700000002000, level: "WARN", message: "Low memory", loggerName: "sys" },
    ],
    sseConnected: true,
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

function renderLogs() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={["/manage/logs"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <LogsPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
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
    // Assuming badges have the text INFO, ERROR, WARN
    expect(screen.getAllByText("INFO").length).toBeGreaterThan(0);
    expect(screen.getAllByText("ERROR").length).toBeGreaterThan(0);
    expect(screen.getAllByText("WARN").length).toBeGreaterThan(0);
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
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true
      });
    } else {
      vi.spyOn(navigator.clipboard, 'writeText').mockImplementation(writeTextMock);
    }

    renderLogs();
    const user = userEvent.setup();
    
    // There are multiple copy buttons, pick the first one
    const copyBtns = screen.getAllByTestId("copy-log-btn");
    await user.click(copyBtns[0]);
    
    expect(writeTextMock).toHaveBeenCalled();
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
});
