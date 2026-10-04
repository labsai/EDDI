import { describe, it, expect, vi, beforeAll, afterAll, beforeEach } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { LiveLogViewer } from "@/components/debugger/live-log-viewer";

// Mock scrollTo for jsdom
beforeAll(() => {
  Element.prototype.scrollTo = vi.fn();
});

afterAll(() => {
  // @ts-expect-error restore
  delete Element.prototype.scrollTo;
});

// Mock the logs API module
vi.mock("@/lib/api/logs", () => ({
  createLogEventSource: vi.fn(() => ({
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    onmessage: null,
    onopen: null,
    onerror: null,
    close: vi.fn(),
  })),
  getRecentLogs: vi.fn(() => Promise.resolve([])),
}));

import { createLogEventSource, getRecentLogs } from "@/lib/api/logs";
const mockCreateLogEventSource = vi.mocked(createLogEventSource);
const mockGetRecentLogs = vi.mocked(getRecentLogs);

describe("LiveLogViewer", () => {
  beforeEach(() => {
    mockGetRecentLogs.mockClear();
    mockCreateLogEventSource.mockClear();
  });

  it("shows empty state when no agentId", () => {
    renderWithProviders(
      <LiveLogViewer agentId={null} conversationId={null} />
    );
    expect(
      screen.getByText("Select an agent to view logs")
    ).toBeInTheDocument();
  });

  it("shows toolbar when agentId is provided", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByTestId("live-log-viewer")).toBeInTheDocument();
  });

  it("shows waiting message when no logs", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByText("Waiting for logs...")).toBeInTheDocument();
  });

  it("renders level filter buttons", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByTestId("filter-ERROR")).toBeInTheDocument();
    expect(screen.getByTestId("filter-WARN")).toBeInTheDocument();
    expect(screen.getByTestId("filter-INFO")).toBeInTheDocument();
    expect(screen.getByTestId("filter-DEBUG")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByTestId("log-search")).toBeInTheDocument();
  });

  it("renders pause button", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByTestId("log-pause")).toBeInTheDocument();
  });

  it("renders clear button", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByTestId("log-clear")).toBeInTheDocument();
  });

  it("shows log entries when loaded from API", async () => {
    mockGetRecentLogs.mockResolvedValueOnce([
      {
        timestamp: Date.now(),
        level: "INFO",
        loggerName: "com.example.TestLogger",
        message: "Application started successfully",
      },
    ]);

    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );

    await waitFor(() => {
      expect(screen.getByText("Application started successfully")).toBeInTheDocument();
    });
    // Logger short name
    expect(screen.getByText(/TestLogger/)).toBeInTheDocument();
  });

  it("toggles level filter on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );

    const errorBtn = screen.getByTestId("filter-ERROR");
    expect(errorBtn).toHaveAttribute("aria-pressed", "false");

    await user.click(errorBtn);
    expect(errorBtn).toHaveAttribute("aria-pressed", "true");

    // Click again to deactivate
    await user.click(errorBtn);
    expect(errorBtn).toHaveAttribute("aria-pressed", "false");
  });

  it("has a log output region with role=log", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    expect(screen.getByRole("log")).toBeInTheDocument();
  });

  it("creates event source when agentId is provided", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-456" conversationId="conv-789" />
    );
    expect(mockCreateLogEventSource).toHaveBeenCalledWith({
      agentId: "agent-456",
      conversationId: "conv-789",
    });
  });

  it("fetches recent logs on mount", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-456" conversationId={null} />
    );
    expect(mockGetRecentLogs).toHaveBeenCalledWith({
      agentId: "agent-456",
      conversationId: undefined,
      limit: 50,
    });
  });

  it("filters logs by search query", async () => {
    mockGetRecentLogs.mockResolvedValueOnce([
      {
        timestamp: Date.now(),
        level: "INFO",
        loggerName: "com.example.AppLogger",
        message: "Application started",
      },
      {
        timestamp: Date.now(),
        level: "ERROR",
        loggerName: "com.example.DbLogger",
        message: "Database connection failed",
      },
    ]);

    const user = userEvent.setup();
    renderWithProviders(
      <LiveLogViewer agentId="agent-search" conversationId={null} />
    );

    await waitFor(() => {
      expect(screen.getByText("Application started")).toBeInTheDocument();
    });
    expect(screen.getByText("Database connection failed")).toBeInTheDocument();

    // Search for "Database"
    const searchInput = screen.getByTestId("log-search");
    await user.type(searchInput, "Database");

    // "Application started" should be filtered out
    expect(screen.queryByText("Application started")).not.toBeInTheDocument();
    expect(screen.getByText("Database connection failed")).toBeInTheDocument();
  });

  it("clears all logs when clear button clicked", async () => {
    mockGetRecentLogs.mockResolvedValueOnce([
      {
        timestamp: Date.now(),
        level: "INFO",
        loggerName: "com.example.Logger",
        message: "Some log message",
      },
    ]);

    const user = userEvent.setup();
    renderWithProviders(
      <LiveLogViewer agentId="agent-clear" conversationId={null} />
    );

    await waitFor(() => {
      expect(screen.getByText("Some log message")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("log-clear"));
    expect(screen.queryByText("Some log message")).not.toBeInTheDocument();
    expect(screen.getByText("Waiting for logs...")).toBeInTheDocument();
  });

  it("connection status indicator shows disconnected initially", () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-123" conversationId={null} />
    );
    const status = screen.getByRole("status", { name: "Disconnected" });
    expect(status).toHaveAttribute("aria-label", "Disconnected");
  });

  // ── Regressions ────────────────────────────────────────────────────────

  function latestSource() {
    const results = mockCreateLogEventSource.mock.results;
    return results[results.length - 1]!.value as {
      onmessage: ((e: MessageEvent) => void) | null;
      onopen: (() => void) | null;
    };
  }

  function emit(entry: Record<string, unknown>) {
    act(() => {
      latestSource().onmessage?.(
        new MessageEvent("message", { data: JSON.stringify(entry) })
      );
    });
  }

  const line = (timestamp: number, message: string) => ({
    timestamp,
    level: "INFO",
    loggerName: "com.example.L",
    message,
  });

  it("merges the initial fetch with live lines instead of replacing them, oldest first", async () => {
    let resolveSeed: (v: unknown[]) => void = () => {};
    mockGetRecentLogs.mockImplementationOnce(
      () => new Promise((r) => (resolveSeed = r as typeof resolveSeed)) as never
    );

    renderWithProviders(
      <LiveLogViewer agentId="agent-merge" conversationId={null} />
    );

    // A live line lands before the REST seed resolves…
    emit(line(3000, "live line"));
    expect(screen.getByText("live line")).toBeInTheDocument();

    // …and the seed (newest-first from the backend, overlapping the replay)
    // must neither discard it nor render upside down.
    await act(async () => {
      resolveSeed([line(2000, "second"), line(1000, "first"), line(3000, "live line")]);
    });

    const messages = screen
      .getAllByTestId("log-entry")
      .map((el) => el.textContent ?? "");
    expect(messages).toHaveLength(3);
    expect(messages[0]).toContain("first");
    expect(messages[1]).toContain("second");
    expect(messages[2]).toContain("live line");
  });

  it("does not make the 500-line stream a live region; announces a throttled error count instead", async () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-aria" conversationId={null} />
    );
    expect(screen.getByRole("log")).toHaveAttribute("aria-live", "off");
    const announcer = screen.getByTestId("log-error-announcer");
    expect(announcer).toHaveTextContent("");

    emit(line(1000, "info line"));
    emit({ ...line(2000, "boom"), level: "ERROR" });
    emit({ ...line(3000, "boom 2"), level: "ERROR" });
    // Throttled: nothing is read out per line…
    expect(announcer).toHaveTextContent("");
    // …the count arrives once, after the burst settles.
    await waitFor(() => expect(announcer).toHaveTextContent("2 errors in the log"), {
      timeout: 5000,
    });
  });

  it("still announces during a sustained error stream (throttle, not debounce)", async () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-sustained" conversationId={null} />
    );
    const announcer = screen.getByTestId("log-error-announcer");
    // An error every 1s, i.e. never a 3s quiet gap.
    for (let i = 1; i <= 4; i++) {
      emit({ ...line(i * 1000, `e${i}`), level: "ERROR" });
      await act(async () => {
        await new Promise((r) => setTimeout(r, 1000));
      });
    }
    expect(announcer.textContent).toMatch(/\d+ errors? in the log/);
  }, 15000);

  it("pause freezes the view but keeps collecting, so resume shows what arrived meanwhile", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LiveLogViewer agentId="agent-pause" conversationId={null} />
    );
    emit(line(1000, "before pause"));

    await user.click(screen.getByTestId("log-pause"));
    emit(line(2000, "during pause"));
    expect(screen.queryByText("during pause")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("log-pause"));
    expect(screen.getByText("during pause")).toBeInTheDocument();
    expect(screen.getByText("before pause")).toBeInTheDocument();
  });

  it("drops a seed from the previous scope that resolves after the switch", async () => {
    let resolveOldSeed: (v: unknown[]) => void = () => {};
    mockGetRecentLogs.mockImplementationOnce(
      () => new Promise((r) => (resolveOldSeed = r as typeof resolveOldSeed)) as never
    );

    const { rerender } = renderWithProviders(
      <LiveLogViewer agentId="agent-old" conversationId="conv-old" />
    );
    rerender(<LiveLogViewer agentId="agent-new" conversationId="conv-new" />);
    emit(line(2000, "new scope line"));

    // The old conversation's history arrives late; it is not this log's.
    await act(async () => {
      resolveOldSeed([line(1000, "old scope line")]);
    });

    expect(screen.getByText("new scope line")).toBeInTheDocument();
    expect(screen.queryByText("old scope line")).not.toBeInTheDocument();
  });

  it("re-seeds on a reconnect, not only on mount", async () => {
    renderWithProviders(
      <LiveLogViewer agentId="agent-reopen" conversationId={null} />
    );
    expect(mockGetRecentLogs).toHaveBeenCalledTimes(1);
    act(() => latestSource().onopen?.()); // first open — seed already running
    expect(mockGetRecentLogs).toHaveBeenCalledTimes(1);
    act(() => latestSource().onopen?.()); // reconnect after a drop
    expect(mockGetRecentLogs).toHaveBeenCalledTimes(2);
  });
});
