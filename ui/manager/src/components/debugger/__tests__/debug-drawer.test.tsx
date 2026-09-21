import { beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { DebugDrawer } from "@/components/debugger/debug-drawer";
import { useDebugStore } from "@/hooks/use-debug-events";

// Use agentId: null to prevent LiveLogViewer from creating EventSource (not available in jsdom)
function renderDrawer(props = { conversationId: "conv-1", agentId: null as string | null }) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <DebugDrawer {...props} />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("DebugDrawer", () => {
  beforeEach(() => {
    // Reset Zustand store to closed state before each test
    useDebugStore.setState({
      isDebugOpen: false,
      activeTab: "pipeline",
      turns: [],
      currentTurnEvents: [],
      currentTurnStart: 0,
      selectedTurnIndex: null,
    });
  });

  it("renders toggle button", () => {
    renderDrawer();
    expect(screen.getByTestId("debug-toggle")).toBeInTheDocument();
  });

  it("toggle button has accessible aria-expanded", () => {
    renderDrawer();
    const toggle = screen.getByTestId("debug-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("opens drawer on toggle click", async () => {
    renderDrawer();
    fireEvent.click(screen.getByTestId("debug-toggle"));

    await waitFor(() => {
      expect(screen.getByTestId("debug-toggle")).toHaveAttribute("aria-expanded", "true");
    });
  });

  it("renders all 5 tabs when open", async () => {
    useDebugStore.setState({ isDebugOpen: true });
    renderDrawer();

    await waitFor(() => {
      expect(screen.getByTestId("debug-tab-pipeline")).toBeInTheDocument();
      expect(screen.getByTestId("debug-tab-costs")).toBeInTheDocument();
      expect(screen.getByTestId("debug-tab-memory")).toBeInTheDocument();
      expect(screen.getByTestId("debug-tab-logs")).toBeInTheDocument();
      expect(screen.getByTestId("debug-tab-prompt")).toBeInTheDocument();
    });
  });

  it("tabs have proper ARIA roles", async () => {
    useDebugStore.setState({ isDebugOpen: true });
    renderDrawer();

    await waitFor(() => {
      const pipelineTab = screen.getByTestId("debug-tab-pipeline");
      expect(pipelineTab).toHaveAttribute("role", "tab");
      expect(pipelineTab).toHaveAttribute("aria-selected", "true");
    });
  });

  it("switches tab on click", async () => {
    useDebugStore.setState({ isDebugOpen: true });
    renderDrawer();

    fireEvent.click(screen.getByTestId("debug-tab-costs"));

    await waitFor(() => {
      expect(screen.getByTestId("debug-tab-costs")).toHaveAttribute("aria-selected", "true");
      expect(screen.getByTestId("debug-tab-pipeline")).toHaveAttribute("aria-selected", "false");
    });
  });

  it("renders pipeline trace by default when open", async () => {
    useDebugStore.setState({ isDebugOpen: true });
    renderDrawer();

    await waitFor(() => {
      expect(screen.getByTestId("pipeline-trace")).toBeInTheDocument();
    });
  });

  it("renders cost dashboard when costs tab selected", async () => {
    useDebugStore.setState({ isDebugOpen: true, activeTab: "costs" });
    renderDrawer();

    await waitFor(() => {
      expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    });
  });

  // NOTE: Memory inspector tab tested separately to avoid MSW handler conflicts
  // with the agents/:convId route (detailed conversation endpoint).

  it("does not render tab content when closed", () => {
    renderDrawer();
    expect(screen.queryByTestId("pipeline-trace")).not.toBeInTheDocument();
    expect(screen.queryByTestId("cost-dashboard")).not.toBeInTheDocument();
  });
});
