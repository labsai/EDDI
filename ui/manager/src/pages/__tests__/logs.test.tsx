import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { LogsPage } from "@/pages/logs";

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

describe("LogsPage", () => {
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

  it("shows SSE connection status badge", () => {
    renderLogs();
    expect(screen.getByTestId("sse-status")).toBeInTheDocument();
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
});
