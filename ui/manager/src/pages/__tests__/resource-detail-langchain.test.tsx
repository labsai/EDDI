import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderPage(type: string, id = "res1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/resources/${type}/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/manage/resources/:type/:id"
              element={<ResourceDetailPage />}
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("LangChain Editor", () => {
  it("renders langchain editor with tasks", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("langchain-editor")).toBeInTheDocument();
    });
  });

  it("renders task card with model type select", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("langchain-task-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders model type dropdown", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("model-type-select").length).toBeGreaterThan(0);
    });
  });

  it("renders mode badge (agent/chat)", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("mode-badge").length).toBeGreaterThan(0);
    });
  });

  it("renders system prompt content editor", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("system-prompt").length).toBeGreaterThan(0);
    });
  });

  it("renders enable built-in tools checkbox", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getAllByTestId("enable-builtin-tools").length).toBeGreaterThan(0);
    });
  });

  it("renders add task button", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("add-task-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("llm");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });
});
