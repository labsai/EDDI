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

describe("HTTP Calls Editor", () => {
  it("renders httpcalls editor", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("apicalls-editor")).toBeInTheDocument();
    });
  });

  it("renders server URL input", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("server-url-input")).toBeInTheDocument();
    });
  });

  it("renders HTTP call editor card", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("httpcall-editor")).toBeInTheDocument();
    });
  });

  it("renders method select dropdown", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("method-select")).toBeInTheDocument();
    });
  });

  it("renders add call button", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("add-call-btn")).toBeInTheDocument();
    });
  });

  it("renders call name input", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("call-name-input")).toBeInTheDocument();
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });
});
