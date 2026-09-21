import { describe, it, expect, vi, beforeAll, afterAll } from "vitest";
import { screen, waitFor } from "@testing-library/react";
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
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-label", "Disconnected");
  });
});
