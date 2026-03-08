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

describe("Output Editor", () => {
  it("renders output editor", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("output-editor")).toBeInTheDocument();
    });
  });

  it("renders output config entries", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-config-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders action name input", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-action-input").length).toBeGreaterThan(0);
    });
  });

  it("renders output item rows", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("output-item-row").length).toBeGreaterThan(0);
    });
  });

  it("renders quick replies section", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("quickreplies-section").length).toBeGreaterThan(0);
    });
  });

  it("renders quick reply rows", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getAllByTestId("quickreply-row").length).toBeGreaterThan(0);
    });
  });

  it("renders add output set button", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("add-output-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("output");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });
});
