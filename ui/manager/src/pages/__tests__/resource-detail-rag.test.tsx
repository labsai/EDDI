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

describe("RAG Knowledge Base Editor", () => {
  // ─── Rendering / Data Population ──────────────────────────

  it("renders rag form editor", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders KB name input from mock data", async () => {
    renderPage("rag");
    await waitFor(() => {
      const input = screen.getByTestId("kb-name") as HTMLInputElement;
      expect(input.value).toBe("product-docs");
    });
  });

  it("renders embedding provider dropdown from mock data", async () => {
    renderPage("rag");
    await waitFor(() => {
      const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
      expect(select.value).toBe("openai");
    });
  });

  it("renders all 7 embedding providers in dropdown", async () => {
    renderPage("rag");
    await waitFor(() => {
      const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
      const options = Array.from(select.options).map((o) => o.value);
      expect(options).toEqual([
        "openai",
        "azure-openai",
        "ollama",
        "mistral",
        "bedrock",
        "cohere",
        "vertex",
      ]);
    });
  });

  it("renders vector store selection with pgvector selected", async () => {
    renderPage("rag");
    await waitFor(() => {
      const pgBtn = screen.getByTestId("store-pgvector");
      expect(pgBtn).toBeInTheDocument();
      // pgvector should have the highlighted ring
      expect(pgBtn.className).toContain("ring");
    });
  });

  it("renders max results slider with mock value", async () => {
    renderPage("rag");
    await waitFor(() => {
      const slider = screen.getByTestId("max-results") as HTMLInputElement;
      expect(slider.value).toBe("5");
    });
  });

  it("renders min score slider with mock value", async () => {
    renderPage("rag");
    await waitFor(() => {
      const slider = screen.getByTestId("min-score") as HTMLInputElement;
      expect(slider.value).toBe("0.6");
    });
  });

  it("renders all five store type buttons", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("store-in-memory")).toBeInTheDocument();
      expect(screen.getByTestId("store-pgvector")).toBeInTheDocument();
      expect(screen.getByTestId("store-mongodb-atlas")).toBeInTheDocument();
      expect(screen.getByTestId("store-elasticsearch")).toBeInTheDocument();
      expect(screen.getByTestId("store-qdrant")).toBeInTheDocument();
    });
  });

  // ─── Interaction Tests ────────────────────────────────────

  it("marks dirty when KB name is changed", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("kb-name")).toBeInTheDocument();
    });

    const input = screen.getByTestId("kb-name") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "my-new-kb" } });

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  it("switches embedding provider via dropdown", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "mistral" } });

    await waitFor(() => {
      expect(select.value).toBe("mistral");
    });
  });

  it("switches to azure-openai provider", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "azure-openai" } });

    await waitFor(() => {
      expect(select.value).toBe("azure-openai");
    });
  });

  it("switches store type and highlights the new selection", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("store-pgvector")).toBeInTheDocument();
    });

    const qdrantBtn = screen.getByTestId("store-qdrant");
    fireEvent.click(qdrantBtn);

    await waitFor(() => {
      // Qdrant should now have the ring highlight
      expect(qdrantBtn.className).toContain("ring");
      // pgvector should no longer have it
      const pgBtn = screen.getByTestId("store-pgvector");
      expect(pgBtn.className).not.toContain("ring");
    });
  });

  it("switches to elasticsearch store", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("store-elasticsearch")).toBeInTheDocument();
    });

    const esBtn = screen.getByTestId("store-elasticsearch");
    fireEvent.click(esBtn);

    await waitFor(() => {
      expect(esBtn.className).toContain("ring");
    });
  });

  it("changes max results slider value", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("max-results")).toBeInTheDocument();
    });

    const slider = screen.getByTestId("max-results") as HTMLInputElement;
    fireEvent.change(slider, { target: { value: "10" } });

    await waitFor(() => {
      expect(slider.value).toBe("10");
    });
  });

  it("changes min score slider value", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("min-score")).toBeInTheDocument();
    });

    const slider = screen.getByTestId("min-score") as HTMLInputElement;
    fireEvent.change(slider, { target: { value: "0.8" } });

    await waitFor(() => {
      expect(slider.value).toBe("0.8");
    });
  });

  // ─── Collapsed Section Expansion Tests ────────────────────

  it("expands chunking section to reveal chunk size slider", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });

    // Chunking section is collapsed by default
    expect(screen.queryByTestId("chunk-size")).not.toBeInTheDocument();

    // Find the chunking section header button and click it
    const sectionButtons = screen.getAllByRole("button", { expanded: false });
    const chunkingBtn = sectionButtons.find((btn) =>
      btn.textContent?.toLowerCase().includes("chunking"),
    );
    expect(chunkingBtn).toBeDefined();
    fireEvent.click(chunkingBtn!);

    await waitFor(() => {
      const slider = screen.getByTestId("chunk-size") as HTMLInputElement;
      expect(slider.value).toBe("512");
    });
  });

  it("expands chunking section and validates overlap slider", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });

    // Click to expand chunking
    const sectionButtons = screen.getAllByRole("button", { expanded: false });
    const chunkingBtn = sectionButtons.find((btn) =>
      btn.textContent?.toLowerCase().includes("chunking"),
    );
    fireEvent.click(chunkingBtn!);

    await waitFor(() => {
      const slider = screen.getByTestId("chunk-overlap") as HTMLInputElement;
      expect(slider.value).toBe("64");
    });
  });

  it("changes chunk strategy from dropdown", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });

    // Expand chunking section
    const sectionButtons = screen.getAllByRole("button", { expanded: false });
    const chunkingBtn = sectionButtons.find((btn) =>
      btn.textContent?.toLowerCase().includes("chunking"),
    );
    fireEvent.click(chunkingBtn!);

    await waitFor(() => {
      expect(screen.getByTestId("chunk-strategy")).toBeInTheDocument();
    });

    const select = screen.getByTestId("chunk-strategy") as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "sentence" } });

    await waitFor(() => {
      expect(select.value).toBe("sentence");
    });
  });

  // ─── JSON Tab Switch ──────────────────────────────────────

  it("switches to JSON tab and shows JSON view", async () => {
    renderPage("rag");
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });
});
