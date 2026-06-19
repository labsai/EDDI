import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route, useNavigate } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { AgentDetailPage } from "@/pages/agent-detail";
import { ResourceListPage } from "@/pages/resource-list";

// Monaco doesn't render in JSDOM, so we mock it
vi.mock("@monaco-editor/react", () => ({
  default: ({
    value,
    onChange,
  }: {
    value: string;
    onChange?: (val: string) => void;
  }) => (
    <textarea
      data-testid="monaco-mock"
      value={value}
      onChange={(e) => onChange?.(e.target.value)}
      aria-label="JSON editor"
    />
  ),
}));

/** Helper component to trigger in-context navigation */
function NavButton({ to, label }: { to: string; label: string }) {
  const navigate = useNavigate();
  return (
    <button data-testid="nav-btn" onClick={() => navigate(to)}>
      {label}
    </button>
  );
}

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

// ---------------------------------------------------------------------------
// Test 1: ResourceDetailPage resets version state on ID change
// ---------------------------------------------------------------------------
describe("ResourceDetailPage – version reset on navigation", () => {
  it("updates resource ID and loads fresh data after navigating to a different resource", async () => {
    const queryClient = createTestQueryClient();

    render(
      <MemoryRouter initialEntries={["/manage/resources/rules/beh1"]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route
                path="/manage/resources/:type/:id"
                element={
                  <>
                    <ResourceDetailPage />
                    <NavButton
                      to="/manage/resources/rules/beh2"
                      label="Go to beh2"
                    />
                  </>
                }
              />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );

    // Wait for beh1 to load — the generic currentversion handler returns 1,
    // so the resource detail page resolves to version 1.
    // The h1 title shows the descriptor name from versionDescriptors.
    await waitFor(
      () => {
        // The config editor layout loads (proves data fetch completed)
        expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );

    // The heading should contain the descriptor name for beh1
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent(/beh1/);

    // Navigate to beh2
    const user = userEvent.setup();
    await user.click(screen.getByText("Go to beh2"));

    // After navigation, the page should show beh2 data — proving the
    // component re-fetched for the new resource rather than showing stale data.
    await waitFor(
      () => {
        const h = screen.getByRole("heading", { level: 1 });
        expect(h).toHaveTextContent(/beh2/);
        // The config editor layout should still be rendered (fresh data loaded)
        expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
  });
});

// ---------------------------------------------------------------------------
// Test 2: AgentDetailPage resets version state on ID change
// ---------------------------------------------------------------------------
describe("AgentDetailPage – version reset on navigation", () => {
  it("shows correct agent data after navigating to a different agent", async () => {
    const queryClient = createTestQueryClient();
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={["/manage/agentview/agent1"]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route
                path="/manage/agentview/:id"
                element={
                  <>
                    <AgentDetailPage />
                    <NavButton
                      to="/manage/agentview/agent2"
                      label="Go to agent2"
                    />
                  </>
                }
              />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );

    // Wait for agent1 to fully load
    await waitFor(
      () => {
        expect(screen.getByText("agent1")).toBeInTheDocument();
        expect(screen.getByTestId("deployment-status")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );

    // Navigate to agent2
    await user.click(screen.getByText("Go to agent2"));

    // After navigation, verify the page shows agent2's ID — proving
    // the component reset its internal state and re-fetched for the new agent.
    // (The descriptor name may lag briefly due to keepPreviousData.)
    await waitFor(
      () => {
        expect(screen.getByText("agent2")).toBeInTheDocument();
        expect(screen.getByTestId("deployment-status")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
  });
});

// ---------------------------------------------------------------------------
// Test 3: ResourceListPage resets search on type change
// ---------------------------------------------------------------------------
describe("ResourceListPage – search reset on type change", () => {
  it("clears search input when navigating to a different resource type", async () => {
    const queryClient = createTestQueryClient();
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={["/manage/resources/rules"]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route
                path="/manage/resources/:type"
                element={
                  <>
                    <ResourceListPage />
                    <NavButton
                      to="/manage/resources/llm"
                      label="Go to LLM"
                    />
                  </>
                }
              />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );

    // Wait for the search input to appear
    await waitFor(() => {
      expect(screen.getByTestId("resource-search")).toBeInTheDocument();
    });

    // Type a search term
    const searchInput = screen.getByTestId("resource-search");
    await user.type(searchInput, "weather");
    expect(searchInput).toHaveValue("weather");

    // Navigate to the LLM resource type
    await user.click(screen.getByText("Go to LLM"));

    // The search input should be cleared after navigation
    await waitFor(() => {
      const input = screen.getByTestId("resource-search");
      expect(input).toHaveValue("");
    });
  });
});
