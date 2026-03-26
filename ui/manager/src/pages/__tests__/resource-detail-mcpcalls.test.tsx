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
      expect(screen.getByTestId("mcp-call-editor")).toBeInTheDocument();
    });
  });

  it("renders tool name input inside call editor", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("tool-name-input")).toBeInTheDocument();
    });
  });

  it("renders trigger actions for call", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByTestId("call-actions")).toBeInTheDocument();
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
      expect(input.value).toBe("http://localhost:7070/mcp");
    });
  });

  it("populates display name from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      const input = screen.getByTestId("mcp-name-input") as HTMLInputElement;
      expect(input.value).toBe("Document Tools Server");
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
      const input = screen.getByTestId("mcp-apikey-input") as HTMLInputElement;
      expect(input.value).toBe("${vault:mcp-doc-key}");
    });
  });

  it("populates tool name in call editor from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      const input = screen.getByTestId("tool-name-input") as HTMLInputElement;
      expect(input.value).toBe("search_documents");
    });
  });

  it("renders whitelist tags from mock data", async () => {
    renderPage("mcpcalls");

    await waitFor(() => {
      expect(screen.getByText("search_documents")).toBeInTheDocument();
      expect(screen.getByText("index_document")).toBeInTheDocument();
    });
  });
});
