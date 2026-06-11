import { describe, it, expect } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { WorkflowDetailPage } from "@/pages/workflow-detail";

function renderWorkflowPage(id = "wf1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter
      initialEntries={[`/manage/workflows/${id}?agentId=agent1&agentVer=1`]}
    >
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/manage/workflows/:id"
              element={<WorkflowDetailPage />}
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("Workflow Detail — Parser Inline Editing", () => {
  it("renders pipeline with parser steps", async () => {
    renderWorkflowPage();
    await waitFor(() => {
      expect(screen.getByTestId("pipeline-list")).toBeInTheDocument();
    });
  });

  it("shows Configure button for inline parser step (no config URI)", async () => {
    renderWorkflowPage();
    await waitFor(() => {
      // The mock workflow has an inline parser step at index 1 (no config.uri)
      expect(screen.getByTestId("configure-inline-1")).toBeInTheDocument();
    });
  });

  it("does NOT show Configure button for parser steps with config URI", async () => {
    renderWorkflowPage();
    await waitFor(() => {
      expect(screen.getByTestId("pipeline-list")).toBeInTheDocument();
    });
    // The parser step at index 0 has a config.uri — no Configure button
    expect(screen.queryByTestId("configure-inline-0")).not.toBeInTheDocument();
  });

  it("opens parser dialog when Configure is clicked", async () => {
    renderWorkflowPage();

    await waitFor(() => {
      expect(screen.getByTestId("configure-inline-1")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId("configure-inline-1"));

    await waitFor(() => {
      expect(screen.getByTestId("parser-edit-dialog")).toBeInTheDocument();
      expect(screen.getByTestId("parser-editor")).toBeInTheDocument();
    });
  });

  it("closes parser dialog on cancel", async () => {
    renderWorkflowPage();

    await waitFor(() => {
      expect(screen.getByTestId("configure-inline-1")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId("configure-inline-1"));

    await waitFor(() => {
      expect(screen.getByTestId("parser-edit-dialog")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId("parser-dialog-cancel"));

    await waitFor(() => {
      expect(screen.queryByTestId("parser-edit-dialog")).not.toBeInTheDocument();
    });
  });

  it("applies parser changes and marks workflow dirty", async () => {
    renderWorkflowPage();

    await waitFor(() => {
      expect(screen.getByTestId("configure-inline-1")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId("configure-inline-1"));

    await waitFor(() => {
      expect(screen.getByTestId("parser-editor")).toBeInTheDocument();
    });

    // Make a change (toggle appendExpressions)
    const toggle = within(screen.getByTestId("toggle-appendExpressions")).getByRole("checkbox");
    await userEvent.click(toggle);

    // Click Apply
    await userEvent.click(screen.getByTestId("parser-dialog-save"));

    // Dialog should close
    await waitFor(() => {
      expect(screen.queryByTestId("parser-edit-dialog")).not.toBeInTheDocument();
    });

    // Workflow should now be dirty
    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });
});
