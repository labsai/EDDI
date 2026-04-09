import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderPage(type: string, id = "snip1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/resources/${type}/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-snippets">
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

describe("Snippet Editor", () => {
  it("renders the snippet editor container", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-editor")).toBeInTheDocument();
    });
  });

  it("renders snippet name input from mock data", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-name")).toBeInTheDocument();
    });
    const input = screen.getByTestId("snippet-name") as HTMLInputElement;
    expect(input.value).toBe("cautious_mode");
  });

  it("renders category select", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-category")).toBeInTheDocument();
    });
    const select = screen.getByTestId("snippet-category") as HTMLSelectElement;
    expect(select.value).toBe("governance");
  });

  it("renders description input", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-description")).toBeInTheDocument();
    });
  });

  it("renders template enabled checkbox", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-template-enabled")).toBeInTheDocument();
    });
    const checkbox = screen.getByTestId("snippet-template-enabled") as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
  });

  it("shows usage hint with snippet name", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByText(/snippets\.cautious_mode/)).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("allows changing the category", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-category")).toBeInTheDocument();
    });
    const select = screen.getByTestId("snippet-category") as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "compliance" } });
    expect(select.value).toBe("compliance");
  });

  it("normalizes name input to lowercase with underscores", async () => {
    renderPage("snippets");
    await waitFor(() => {
      expect(screen.getByTestId("snippet-name")).toBeInTheDocument();
    });
    const input = screen.getByTestId("snippet-name") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "My Test Name!" } });
    // The component transforms input: toLowerCase + replace non-alphanumeric with _
    expect(input.value).toMatch(/^[a-z0-9_]+$/);
  });
});
