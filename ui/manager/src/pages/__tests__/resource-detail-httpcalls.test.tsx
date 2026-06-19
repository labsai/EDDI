import { describe, expect, it } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
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
      expect(screen.getAllByTestId("httpcall-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders method select dropdown", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getAllByTestId("method-select").length).toBeGreaterThan(0);
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
      expect(screen.getAllByTestId("call-name-input").length).toBeGreaterThan(0);
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderPage("apicalls");

    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  // ─── Interaction tests ───────────────────────────────────────────────

  it("clicking add call button adds a new call editor", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      expect(screen.getByTestId("add-call-btn")).toBeInTheDocument();
    });

    const initialCalls = screen.getAllByTestId("httpcall-editor").length;
    fireEvent.click(screen.getByTestId("add-call-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("httpcall-editor").length).toBe(
        initialCalls + 1
      );
    });
  });

  it("changing method select updates value", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      expect(screen.getAllByTestId("method-select").length).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("method-select")[0]! as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "POST" } });

    await waitFor(() => {
      expect(select.value).toBe("POST");
    });
  });

  it("renders call editor cards with path inputs", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      const editors = screen.getAllByTestId("httpcall-editor");
      expect(editors.length).toBeGreaterThan(0);
      // Each call editor should have input elements for path
      const inputs = editors[0]!.querySelectorAll("input");
      expect(inputs.length).toBeGreaterThan(0);
    });
  });

  it("editing call name updates the value", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      expect(screen.getAllByTestId("call-name-input").length).toBeGreaterThan(0);
    });

    const input = screen.getAllByTestId("call-name-input")[0]! as HTMLInputElement;
    fireEvent.change(input, { target: { value: "my-api-call" } });

    await waitFor(() => {
      expect(input.value).toBe("my-api-call");
    });
  });

  it("switches to JSON tab and shows JSON view", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("renders server URL from mock data", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      const input = screen.getByTestId("server-url-input") as HTMLInputElement;
      expect(input.value).toBeTruthy();
    });
  });

  it("editing server URL updates the value", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      expect(screen.getByTestId("server-url-input")).toBeInTheDocument();
    });

    const input = screen.getByTestId("server-url-input") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "https://new-api.example.com" } });

    await waitFor(() => {
      expect(input.value).toBe("https://new-api.example.com");
    });
  });

  it("renders method select with HTTP methods", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      const selects = screen.getAllByTestId("method-select");
      expect(selects.length).toBeGreaterThan(0);
      const select = selects[0]! as HTMLSelectElement;
      // Verify it has HTTP method options
      const options = select.querySelectorAll("option");
      expect(options.length).toBeGreaterThanOrEqual(4);
    });
  });

  it("marks dirty indicator when server URL changes", async () => {
    renderPage("apicalls");
    await waitFor(() => {
      expect(screen.getByTestId("server-url-input")).toBeInTheDocument();
    });

    const input = screen.getByTestId("server-url-input") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "https://changed-url.com" } });

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });
});

