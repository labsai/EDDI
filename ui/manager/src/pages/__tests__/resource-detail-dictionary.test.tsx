import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";
import userEvent from "@testing-library/user-event";

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

describe("Dictionary Editor", () => {
  it("renders dictionary editor", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getByTestId("dictionary-editor")).toBeInTheDocument();
    });
  });

  it("renders word rows", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBeGreaterThan(0);
    });
  });

  it("renders phrase rows", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getAllByTestId("phrase-row").length).toBeGreaterThan(0);
    });
  });

  it("renders regex rows", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getAllByTestId("regex-row").length).toBeGreaterThan(0);
    });
  });

  it("renders add word button", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getByTestId("add-words-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("dictionary");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  // ─── Interaction tests ──────────────────────────────────────────────────

  it("clicking add word button adds a new word row", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("add-words-btn")).toBeInTheDocument();
    });

    const initialCount = screen.getAllByTestId("word-row").length;
    await user.click(screen.getByTestId("add-words-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("word-row").length).toBe(initialCount + 1);
    });
  });

  it("switching to JSON tab shows JSON view", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("switching back to form tab shows form view", async () => {
    renderPage("dictionary");
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    // Go to JSON
    await user.click(screen.getByTestId("tab-json"));
    expect(screen.getByTestId("json-view")).toBeInTheDocument();

    // Go back to Form
    await user.click(screen.getByTestId("tab-form"));
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
  });
});
