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

describe("Rules Editor", () => {
  it("renders Rules Editor with groups", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("rules-editor")).toBeInTheDocument();
    });
  });

  it("renders behavior group with rules", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("rules-group")).toBeInTheDocument();
    });
  });

  it("renders rule editors with name inputs", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("rule-editor").length
      ).toBeGreaterThan(0);
    });
  });

  it("renders condition type dropdowns", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });
  });

  it("renders add group button", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("add-group-btn")).toBeInTheDocument();
    });
  });

  it("renders add rule buttons", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("add-rule-btn").length
      ).toBeGreaterThan(0);
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("condition type dropdown includes deploymentContext and capabilityMatch", async () => {
    renderPage("rules");

    await waitFor(() => {
      expect(
        screen.getAllByTestId("condition-type-select").length
      ).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("condition-type-select")[0] as HTMLSelectElement;
    const options = Array.from(select.options).map((o) => o.value);
    expect(options).toContain("deploymentContext");
    expect(options).toContain("capabilityMatch");
  });
});
