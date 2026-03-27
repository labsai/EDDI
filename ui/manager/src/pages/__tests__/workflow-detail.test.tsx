import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { WorkflowDetailPage } from "@/pages/workflow-detail";

function renderPage(id = "pkg1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/workflowview/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/manage/workflowview/:id"
              element={<WorkflowDetailPage />}
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("WorkflowDetailPage", () => {
  it("renders page heading", async () => {
    renderPage();

    await waitFor(() => {
      // MSW returns descriptor name "Support Workflow" for pkg1
      expect(screen.getByText("Support Workflow")).toBeInTheDocument();
    });
  });

  it("renders pipeline with extension items", async () => {
    renderPage();

    await waitFor(() => {
      // MSW returns a package with 2 extensions (behavior + langchain)
      expect(screen.getByText("Rules")).toBeInTheDocument();
      expect(screen.getByText("Lang Chain")).toBeInTheDocument();
    });
  });

  it("renders extension pipeline section header", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Pipeline")).toBeInTheDocument();
    });
  });

  it("renders add extension button", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId("add-extension-btn")).toBeInTheDocument();
    });
  });

  it("renders save and discard buttons", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId("save-btn")).toBeInTheDocument();
      expect(screen.getByTestId("discard-btn")).toBeInTheDocument();
    });
  });

  it("renders delete button", async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId("delete-pkg-btn")).toBeInTheDocument();
    });
  });
});
