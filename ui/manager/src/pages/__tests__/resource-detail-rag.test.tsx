import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderRagPage(id = "res1") {
  return renderPage(
    `/manage/resources/rag/${id}`,
    <ResourceDetailPage />,
    "/manage/resources/:type/:id"
  );
}

describe("RAG Knowledge Base Editor", () => {
  // ─── Rendering / Data Population ──────────────────────────

  it("renders rag form editor", async () => {
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders KB name input from mock data", async () => {
    renderRagPage();
    await waitFor(() => {
      const input = screen.getByTestId("kb-name") as HTMLInputElement;
      expect(input.value).toBe("product-docs");
    });
  });

  it("renders embedding provider dropdown from mock data", async () => {
    renderRagPage();
    await waitFor(() => {
      const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
      expect(select.value).toBe("openai");
    });
  });

  it("renders all 8 embedding providers in dropdown", async () => {
    renderRagPage();
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
        "gemini",
      ]);
    });
  });

  it("renders vector store selection with pgvector selected", async () => {
    renderRagPage();
    await waitFor(() => {
      const pgBtn = screen.getByTestId("store-pgvector");
      expect(pgBtn).toBeInTheDocument();
      expect(pgBtn.getAttribute("aria-pressed")).toBe("true");
    });
  });

  it("renders max results slider with mock value", async () => {
    renderRagPage();
    await waitFor(() => {
      const slider = screen.getByTestId("max-results") as HTMLInputElement;
      expect(slider.value).toBe("5");
    });
  });

  it("renders min score slider with mock value", async () => {
    renderRagPage();
    await waitFor(() => {
      const slider = screen.getByTestId("min-score") as HTMLInputElement;
      expect(slider.value).toBe("0.6");
    });
  });

  it("renders all five store type buttons", async () => {
    renderRagPage();
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
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("kb-name")).toBeInTheDocument();
    });

    const input = screen.getByTestId("kb-name");
    await user.clear(input);
    await user.type(input, "my-new-kb");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  it("switches embedding provider via dropdown", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
    await user.selectOptions(select, "mistral");

    await waitFor(() => {
      expect(select.value).toBe("mistral");
    });
  });

  it("switches to azure-openai provider", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    const select = screen.getByTestId("embedding-provider") as HTMLSelectElement;
    await user.selectOptions(select, "azure-openai");

    await waitFor(() => {
      expect(select.value).toBe("azure-openai");
    });
  });

  it("switches store type and highlights the new selection", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("store-pgvector")).toBeInTheDocument();
    });

    const qdrantBtn = screen.getByTestId("store-qdrant");
    await user.click(qdrantBtn);

    await waitFor(() => {
      expect(qdrantBtn.getAttribute("aria-pressed")).toBe("true");
      const pgBtn = screen.getByTestId("store-pgvector");
      expect(pgBtn.getAttribute("aria-pressed")).toBe("false");
    });
  });

  it("switches to elasticsearch store", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("store-elasticsearch")).toBeInTheDocument();
    });

    const esBtn = screen.getByTestId("store-elasticsearch");
    await user.click(esBtn);

    await waitFor(() => {
      expect(esBtn.getAttribute("aria-pressed")).toBe("true");
    });
  });

  it("changes max results slider value", async () => {
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("max-results")).toBeInTheDocument();
    });

    const slider = screen.getByTestId("max-results") as HTMLInputElement;
    // Use fireEvent for range input (not user-initiated)
    const { fireEvent } = await import("@testing-library/react");
    fireEvent.change(slider, { target: { value: "10" } });

    await waitFor(() => {
      expect(slider.value).toBe("10");
    });
  });

  it("changes min score slider value", async () => {
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("min-score")).toBeInTheDocument();
    });

    const slider = screen.getByTestId("min-score") as HTMLInputElement;
    const { fireEvent } = await import("@testing-library/react");
    fireEvent.change(slider, { target: { value: "0.8" } });

    await waitFor(() => {
      expect(slider.value).toBe("0.8");
    });
  });

  // ─── Collapsed Section Expansion Tests ────────────────────

  it("expands chunking section to reveal chunk size slider", async () => {
    const user = userEvent.setup();
    renderRagPage();
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
    await user.click(chunkingBtn!);

    await waitFor(() => {
      const slider = screen.getByTestId("chunk-size") as HTMLInputElement;
      expect(slider.value).toBe("512");
    });
  });

  it("expands chunking section and validates overlap slider", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });

    const sectionButtons = screen.getAllByRole("button", { expanded: false });
    const chunkingBtn = sectionButtons.find((btn) =>
      btn.textContent?.toLowerCase().includes("chunking"),
    );
    await user.click(chunkingBtn!);

    await waitFor(() => {
      const slider = screen.getByTestId("chunk-overlap") as HTMLInputElement;
      expect(slider.value).toBe("64");
    });
  });

  it("changes chunk strategy from dropdown", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
    });

    // Expand chunking section
    const sectionButtons = screen.getAllByRole("button", { expanded: false });
    const chunkingBtn = sectionButtons.find((btn) =>
      btn.textContent?.toLowerCase().includes("chunking"),
    );
    await user.click(chunkingBtn!);

    await waitFor(() => {
      expect(screen.getByTestId("chunk-strategy")).toBeInTheDocument();
    });

    const select = screen.getByTestId("chunk-strategy") as HTMLSelectElement;
    await user.selectOptions(select, "sentence");

    await waitFor(() => {
      expect(select.value).toBe("sentence");
    });
  });

  // ─── JSON Tab Switch ──────────────────────────────────────

  it("switches to JSON tab and shows JSON view", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  // ─── NEW: Coverage expansion tests ──────────────────────────────────

  it("switches to ollama embedding provider", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("embedding-provider"), "ollama");

    await waitFor(() => {
      expect((screen.getByTestId("embedding-provider") as HTMLSelectElement).value).toBe("ollama");
    });
  });

  it("switches to bedrock embedding provider", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("embedding-provider"), "bedrock");

    await waitFor(() => {
      expect((screen.getByTestId("embedding-provider") as HTMLSelectElement).value).toBe("bedrock");
    });
  });

  it("switches to gemini embedding provider", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("embedding-provider"), "gemini");

    await waitFor(() => {
      expect((screen.getByTestId("embedding-provider") as HTMLSelectElement).value).toBe("gemini");
    });
  });

  it("switches to in-memory store type", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("store-in-memory")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("store-in-memory"));

    await waitFor(() => {
      expect(screen.getByTestId("store-in-memory").getAttribute("aria-pressed")).toBe("true");
      expect(screen.getByTestId("store-pgvector").getAttribute("aria-pressed")).toBe("false");
    });
  });

  it("switches to mongodb-atlas store type", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => {
      expect(screen.getByTestId("store-mongodb-atlas")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("store-mongodb-atlas"));

    await waitFor(() => {
      expect(screen.getByTestId("store-mongodb-atlas").getAttribute("aria-pressed")).toBe("true");
    });
  });

  it("renders embedding parameters from mock data", async () => {
    renderRagPage();
    await waitFor(() => {
      // Mock data has model: "text-embedding-3-small" as an embedding parameter
      expect(screen.getByDisplayValue("text-embedding-3-small")).toBeInTheDocument();
    });
  });

  it("renders store parameters from mock data", async () => {
    renderRagPage();
    await waitFor(() => {
      // Mock data has host: "localhost" as a store parameter
      expect(screen.getByDisplayValue("localhost")).toBeInTheDocument();
    });
  });
});
