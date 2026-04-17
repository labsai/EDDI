import { describe, it, expect } from "vitest";
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

describe("MCP Calls Editor", () => {
  it("renders mcpcalls form editor", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("mcpcalls-form-editor")).toBeInTheDocument();
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders server connection section with name input", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("mcp-name-input")).toBeInTheDocument();
    });
  });

  it("renders MCP server URL input", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("mcp-url-input")).toBeInTheDocument();
    });
  });

  it("renders transport select dropdown", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("mcp-transport-select")).toBeInTheDocument();
    });
  });

  it("renders API key input", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("mcp-apikey-input")).toBeInTheDocument();
    });
  });

  it("renders tool governance whitelist", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("tools-whitelist")).toBeInTheDocument();
    });
  });

  it("renders tool governance blacklist", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("tools-blacklist")).toBeInTheDocument();
    });
  });

  it("renders MCP call editor card from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders tool name input inside call editor", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getAllByTestId("tool-name-input").length).toBeGreaterThan(0);
    });
  });

  it("renders trigger actions for call", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getAllByTestId("call-actions").length).toBeGreaterThan(0);
    });
  });

  it("renders add MCP call button", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("add-mcp-call")).toBeInTheDocument();
    });
  });

  it("populates server URL from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      const input = screen.getByTestId("mcp-url-input") as HTMLInputElement;
      expect(input.value).toBe("https://mcp.internal.example.com/v1");
    });
  });

  it("populates display name from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      const input = screen.getByTestId("mcp-name-input") as HTMLInputElement;
      expect(input.value).toBe("Enterprise Document Tools Server");
    });
  });

  it("populates transport from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      const select = screen.getByTestId(
        "mcp-transport-select"
      ) as HTMLSelectElement;
      expect(select.value).toBe("http");
    });
  });

  it("populates API key from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      // SecretKeyPicker v2 auto-detects ${eddivault:...} and shows an amber chip
      const picker = screen.getByTestId("mcp-apikey-input");
      expect(picker).toBeInTheDocument();
      // The chip displays the vault key name as text
      expect(picker).toHaveTextContent("mcp-doc-key");
    });
  });

  it("populates tool name in call editor from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      const inputs = screen.getAllByTestId("tool-name-input") as HTMLInputElement[];
      expect(inputs[0]!.value).toBe("search_documents");
    });
  });

  it("renders whitelist tags from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByText("search_documents")).toBeInTheDocument();
      expect(screen.getByText("index_document")).toBeInTheDocument();
    });
  });

  // ─── Tool Discovery Tests ────────────────────────────────────────────────

  it("renders discover tools button", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });
  });

  it("shows discovered tools panel after clicking discover", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovered-tools-panel")).toBeInTheDocument();
    });
  });

  it("shows discovered tool items with names", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      const items = screen.getAllByTestId("discovered-tool-item");
      expect(items.length).toBe(3);
    });
  });

  it("shows tool descriptions in discovered panel", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(
        screen.getByText("Search indexed documents by query")
      ).toBeInTheDocument();
      expect(
        screen.getByText("Delete a document by its unique ID")
      ).toBeInTheDocument();
    });
  });

  it("shows tool count in discovered panel header", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Available Tools.*\(3\)/)).toBeInTheDocument();
    });
  });
});
