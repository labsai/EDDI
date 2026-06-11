import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderPage(type: string, id = "par1") {
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

describe("Parser Resource Detail Page", () => {
  it("renders parser editor in standalone mode", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("parser-editor")).toBeInTheDocument();
    });
  });

  it("renders config section with toggles", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("parser-config-section")).toBeInTheDocument();
    });
  });

  it("renders dictionaries section", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("parser-dictionaries-section")).toBeInTheDocument();
    });
  });

  it("renders corrections section", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("parser-corrections-section")).toBeInTheDocument();
    });
  });

  it("renders normalizers section", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("parser-normalizers-section")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders built-in dictionary toggles with data from backend", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("dict-integer")).toBeInTheDocument();
      expect(screen.getByTestId("dict-decimal")).toBeInTheDocument();
      expect(screen.getByTestId("dict-punctuation")).toBeInTheDocument();
    });
  });

  it("renders regular dictionary from backend data", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("regular-dict-0")).toBeInTheDocument();
    });
  });

  it("renders levenshtein distance input from backend data", async () => {
    renderPage("parser");
    await waitFor(() => {
      expect(screen.getByTestId("levenshtein-distance")).toBeInTheDocument();
    });
  });
});
