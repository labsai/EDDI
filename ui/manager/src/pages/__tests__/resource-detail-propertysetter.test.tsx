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

describe("Property Setter Editor", () => {
  it("renders propertysetter editor", async () => {
    renderPage("propertysetter");
    await waitFor(() => {
      expect(screen.getByTestId("propertysetter-editor")).toBeInTheDocument();
    });
  });

  it("renders setter cards", async () => {
    renderPage("propertysetter");
    await waitFor(() => {
      expect(screen.getAllByTestId("setter-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders property rows", async () => {
    renderPage("propertysetter");
    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBeGreaterThan(0);
    });
  });

  it("renders scope dropdown", async () => {
    renderPage("propertysetter");
    await waitFor(() => {
      expect(screen.getAllByTestId("scope-select").length).toBeGreaterThan(0);
    });
  });

  it("renders add setter button", async () => {
    renderPage("propertysetter");
    await waitFor(() => {
      expect(screen.getByTestId("add-setter-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("propertysetter");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });
});
