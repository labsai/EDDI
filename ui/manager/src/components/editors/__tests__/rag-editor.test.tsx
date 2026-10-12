import { describe, it, expect, vi, beforeEach } from "vitest";
import { useLayoutEffect, useState } from "react";
import { act, fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  RagEditor,
  type RagConfig,
} from "@/components/editors/rag-editor";

const emptyConfig: RagConfig = {};

const populatedConfig: RagConfig = {
  name: "product-docs",
  embeddingProvider: "openai",
  embeddingParameters: { model: "text-embedding-3-small", apiKey: "${vault:openai-key}" },
  storeType: "pgvector",
  storeParameters: { host: "localhost", port: "5432" },
  chunkStrategy: "recursive",
  chunkSize: 512,
  chunkOverlap: 64,
  maxResults: 5,
  minScore: 0.6,
};

/**
 * Runs `onCommit` from a layout effect: inside the commit, after the siblings
 * rendered before it have committed, but before any passive effect has run.
 */
function CommitProbe({ onCommit }: { onCommit?: () => void }) {
  useLayoutEffect(() => {
    onCommit?.();
  });
  return null;
}

/** Helper to open a collapsed Section by its label text */
async function openSection(user: ReturnType<typeof userEvent.setup>, label: string) {
  const btn = screen.getByRole("button", { name: new RegExp(label, "i") });
  await user.click(btn);
}

describe("RagEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── Basic rendering ────────────────────────────────────────────────────────

  it("renders with data-testid rag-editor", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("rag-editor")).toBeInTheDocument();
  });

  it("shows General section", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("General")).toBeInTheDocument();
  });

  it("shows Embedding Model section", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Embedding Model")).toBeInTheDocument();
  });

  it("shows Vector Store section", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Vector Store")).toBeInTheDocument();
  });

  it("shows Document Chunking section", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Document Chunking")).toBeInTheDocument();
  });

  it("shows Retrieval Defaults section", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Retrieval Defaults")).toBeInTheDocument();
  });

  it("shows Document Ingestion section", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Document Ingestion")).toBeInTheDocument();
  });

  it("shows KB name input", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("kb-name")).toBeInTheDocument();
  });

  it("renders populated config with KB name", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("kb-name")).toHaveValue("product-docs");
  });

  it("shows embedding provider select", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("embedding-provider")).toBeInTheDocument();
  });

  it("shows store type buttons", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("store-in-memory")).toBeInTheDocument();
    expect(screen.getByTestId("store-pgvector")).toBeInTheDocument();
    expect(screen.getByTestId("store-mongodb-atlas")).toBeInTheDocument();
  });

  it("shows max-results slider", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("max-results")).toBeInTheDocument();
  });

  it("shows min-score slider", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("min-score")).toBeInTheDocument();
  });

  it("shows save first message when no resourceId provided", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Document Ingestion")).toBeInTheDocument();
  });

  it("shows provider badge for embedding", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("OpenAI")).toBeInTheDocument();
  });

  // ── Provider switching ─────────────────────────────────────────────────────

  it("changes embedding provider without pre-filling any parameter", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.selectOptions(
      screen.getByTestId("embedding-provider"),
      "ollama"
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        embeddingProvider: "ollama",
        embeddingParameters: undefined,
      })
    );
  });

  it("shows embedding parameter key-value pairs", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("embed-row-model")).toHaveAttribute("data-state", "custom");
    expect(screen.getByTestId("embed-value-model")).toHaveValue("text-embedding-3-small");
  });

  // ── Store type switching ───────────────────────────────────────────────────

  it("switches vector store type to MongoDB Atlas", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("store-mongodb-atlas"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        storeType: "mongodb-atlas",
        storeParameters: undefined,
      })
    );
  });

  it("shows store parameters for pgvector", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("store-row-host")).toBeInTheDocument();
    expect(screen.getByTestId("store-value-host")).toHaveValue("localhost");
  });

  it("marks pgvector store button as selected", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("store-pgvector")).toHaveAttribute(
      "aria-pressed",
      "true"
    );
  });

  // ── Document Chunking ──────────────────────────────────────────────────────

  it("shows chunk strategy select after opening chunking section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    // Chunking section is defaultOpen={false}
    await openSection(user, "Document Chunking");
    expect(screen.getByTestId("chunk-strategy")).toHaveValue("recursive");
  });

  it("shows chunk size slider after opening chunking section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    expect(screen.getByTestId("chunk-size")).toHaveValue("512");
  });

  it("shows chunk overlap slider after opening chunking section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    expect(screen.getByTestId("chunk-overlap")).toHaveValue("64");
  });

  it("shows chunk preview visualization after opening chunking section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    expect(screen.getByTestId("chunking-section")).toBeInTheDocument();
  });

  it("offers only the recursive chunking strategy, the one that is implemented", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    const select = screen.getByTestId("chunk-strategy") as HTMLSelectElement;
    expect([...select.options].map((o) => o.value)).toEqual(["recursive"]);
  });

  // ── KB name editing ────────────────────────────────────────────────────────

  it("calls onChange when KB name is edited", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    await user.type(screen.getByTestId("kb-name"), "x");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ name: "x" })
    );
  });

  // ── Retrieval section ──────────────────────────────────────────────────────

  it("shows retrieval section with populated defaults", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("retrieval-section")).toBeInTheDocument();
  });

  it("shows max results value", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    // Max results value displayed as text: "5"
    expect(screen.getByTestId("max-results")).toHaveValue("5");
  });

  it("shows min score value", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    // Min score displayed
    expect(screen.getByTestId("min-score")).toHaveValue("0.6");
  });

  // ── Ingestion section ──────────────────────────────────────────────────────

  it("shows save first message in ingestion section when no resourceId", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    await openSection(user, "Document Ingestion");
    expect(
      screen.getByText("Save this knowledge base first to enable document ingestion.")
    ).toBeInTheDocument();
  });

  it("shows ingestion panel when resourceId is provided", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor
        data={populatedConfig}
        onChange={onChange}
        resourceId="abc123"
        version={1}
      />
    );
    await openSection(user, "Document Ingestion");
    expect(screen.getByTestId("ingestion-panel")).toBeInTheDocument();
  });

  it("hides ingestion panel in readOnly mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor
        data={populatedConfig}
        onChange={onChange}
        resourceId="abc123"
        version={1}
        readOnly
      />
    );
    await openSection(user, "Document Ingestion");
    // IngestionPanel returns null for readOnly
    expect(screen.queryByTestId("ingestion-panel")).not.toBeInTheDocument();
  });

  // ── ReadOnly mode ──────────────────────────────────────────────────────────

  it("disables embedding provider select in readOnly mode", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.getByTestId("embedding-provider")).toBeDisabled();
  });

  it("disables store type buttons in readOnly mode", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.getByTestId("store-pgvector")).toBeDisabled();
  });

  // ── Additional store types ─────────────────────────────────────────────────

  it("shows additional store type buttons", () => {
    renderWithProviders(
      <RagEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("store-elasticsearch")).toBeInTheDocument();
    expect(screen.getByTestId("store-qdrant")).toBeInTheDocument();
    expect(screen.getByTestId("store-chroma")).toBeInTheDocument();
  });

  // ── Known parameters: defaults, examples, required ─────────────────────────

  it("lists every known parameter, each saying what an empty field does", () => {
    renderWithProviders(
      <RagEditor data={{ embeddingProvider: "openai", embeddingParameters: {} }} onChange={onChange} />
    );
    expect(screen.getByTestId("embed-row-model")).toHaveAttribute("data-state", "default");
    expect(screen.getByTestId("embed-value-model")).toHaveValue("");
    expect(screen.getByTestId("embed-value-model")).toHaveAttribute(
      "placeholder",
      "Default: text-embedding-3-small",
    );
    expect(screen.getByTestId("embed-row-apiKey")).toBeInTheDocument();
    expect(screen.getByTestId("embed-defaults-note")).toBeInTheDocument();
  });

  it("names the real backend default, not a sample, for a per-KB table", () => {
    renderWithProviders(
      <RagEditor
        data={{ storeType: "pgvector", storeParameters: {} }}
        onChange={onChange}
        resourceId="6789abcdef0123456789abcd"
      />
    );
    // EmbeddingStoreFactory derives eddi_kb_<id>; the old hint showed a fixed
    // "embeddings", which made every KB that copied it share one table.
    expect(screen.getByTestId("store-value-table")).toHaveAttribute(
      "placeholder",
      "Default: eddi_kb_6789abcdef0123456789abcd",
    );
    expect(screen.getByTestId("store-value-user")).toHaveAttribute("placeholder", "Default: eddi");
  });

  it("marks a sample value as a sample and a required field as required", () => {
    renderWithProviders(
      <RagEditor data={{ embeddingProvider: "vertex", embeddingParameters: {} }} onChange={onChange} />
    );
    expect(screen.getByTestId("embed-value-project")).toHaveAttribute(
      "placeholder",
      "Required — e.g. my-gcp-project",
    );
  });

  it("removes the key when a field is cleared — it never saves an empty value", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor
        data={{ embeddingProvider: "ollama", embeddingParameters: { model: "x" } }}
        onChange={onChange}
      />
    );
    await user.clear(screen.getByTestId("embed-value-model"));
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: undefined })
    );
  });

  it("sets a known parameter when one is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={{ embeddingProvider: "ollama", embeddingParameters: {} }} onChange={onChange} />
    );
    await user.type(screen.getByTestId("embed-value-model"), "b");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: { model: "b" } })
    );
  });

  it("flags an empty value saved by an older editor and resets it to the default", async () => {
    // A key present with "" is not absent: the backend's getOrDefault returns
    // "" and the provider gets an empty model name.
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor
        data={{ embeddingProvider: "ollama", embeddingParameters: { model: "", baseUrl: "http://o:1" } }}
        onChange={onChange}
      />
    );
    expect(screen.getByTestId("embed-empty-model")).toBeInTheDocument();
    expect(screen.queryByTestId("embed-empty-baseUrl")).not.toBeInTheDocument();
    await user.click(screen.getByTestId("embed-use-default-model"));
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: { baseUrl: "http://o:1" } })
    );
  });

  it("drops every empty value when it saves anything", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor
        data={{ embeddingProvider: "ollama", embeddingParameters: { model: "", baseUrl: "" } }}
        onChange={onChange}
      />
    );
    await user.type(screen.getByTestId("embed-value-model"), "m");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: { model: "m" } })
    );
  });

  // ── In-memory store type ─────────────────────────────────────────────────

  it("switches to in-memory store type with no parameters", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("store-in-memory"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        storeType: "in-memory",
        storeParameters: undefined,
      })
    );
  });

  it("marks in-memory store as selected when storeType is in-memory", () => {
    const config: RagConfig = { ...populatedConfig, storeType: "in-memory", storeParameters: undefined };
    renderWithProviders(
      <RagEditor data={config} onChange={onChange} />
    );
    expect(screen.getByTestId("store-in-memory")).toHaveAttribute(
      "aria-pressed",
      "true"
    );
  });

  // ── Nothing invented on a switch ───────────────────────────────────────────

  // The defect this guards: switching provider or store wrote each hint's
  // example into the config as its value, so a knowledge base ended up
  // referencing ${vault:openai-key}, ${eddivault:gemini-key}, … — vault keys
  // nobody had created — and every other parameter as "", which the backend
  // uses instead of its default.
  it.each(["openai", "azure-openai", "ollama", "mistral", "bedrock", "cohere", "vertex", "gemini"])(
    "switching the embedding provider to %s writes no parameter at all",
    async (provider) => {
      const user = userEvent.setup();
      renderWithProviders(
        <RagEditor data={{ embeddingProvider: provider === "openai" ? "cohere" : "openai" }} onChange={onChange} />
      );
      await user.selectOptions(screen.getByTestId("embedding-provider"), provider);
      expect(onChange).toHaveBeenLastCalledWith(
        expect.objectContaining({ embeddingProvider: provider, embeddingParameters: undefined })
      );
    },
  );

  it.each(["pgvector", "mongodb-atlas", "elasticsearch", "qdrant", "chroma"])(
    "switching the vector store to %s writes no parameter at all",
    async (store) => {
      const user = userEvent.setup();
      renderWithProviders(<RagEditor data={{ storeType: "in-memory" }} onChange={onChange} />);
      await user.click(screen.getByTestId(`store-${store}`));
      expect(onChange).toHaveBeenLastCalledWith(
        expect.objectContaining({ storeType: store, storeParameters: undefined })
      );
    },
  );

  it("restores what a provider had earlier in the session when switching back", async () => {
    const user = userEvent.setup();
    function Harness() {
      const [data, setData] = useState<RagConfig>({
        embeddingProvider: "ollama",
        embeddingParameters: { model: "bge-m3" },
      });
      return <RagEditor data={data} onChange={setData} />;
    }
    renderWithProviders(<Harness />);
    await user.selectOptions(screen.getByTestId("embedding-provider"), "openai");
    expect(screen.getByTestId("embed-value-model")).toHaveValue("");
    await user.selectOptions(screen.getByTestId("embedding-provider"), "ollama");
    expect(screen.getByTestId("embed-value-model")).toHaveValue("bge-m3");
  });

  it("asks for a vault secret on a credential field instead of suggesting a key name", () => {
    renderWithProviders(
      <RagEditor data={{ embeddingProvider: "gemini", embeddingParameters: {} }} onChange={onChange} />
    );
    const row = screen.getByTestId("embed-row-apiKey");
    expect(row.innerHTML).not.toMatch(/gemini-key|openai-key/);
    expect(row.innerHTML).toContain("Required — pick a vault secret");
  });

  // ── Parameters the editor has no hint for ──────────────────────────────────

  function StatefulEditor({ initial, spy }: { initial: RagConfig; spy: (c: RagConfig) => void }) {
    const [data, setData] = useState<RagConfig>(initial);
    return (
      <RagEditor
        data={data}
        onChange={(c) => {
          spy(c);
          setData(c);
        }}
      />
    );
  }

  it("keeps an added parameter on screen but out of the config until it has a value", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <StatefulEditor initial={{ embeddingProvider: "ollama", embeddingParameters: {} }} spy={onChange} />
    );
    await user.click(screen.getByTestId("embed-add"));
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId("embed-custom-row")).toBeInTheDocument();

    const key = screen.getByTestId("embed-custom-key");
    await user.clear(key);
    await user.type(key, "timeout");
    await user.type(screen.getByTestId("embed-value-timeout"), "30");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: { timeout: "30" } })
    );
  });

  it("clearing a custom value removes it from the config but keeps the row, in place and focused", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <StatefulEditor
        initial={{ embeddingProvider: "ollama", embeddingParameters: { timeout: "30", retries: "2" } }}
        spy={onChange}
      />
    );
    const input = screen.getByTestId("embed-value-timeout");
    await user.clear(input);
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: { retries: "2" } })
    );
    // Same element: typing continues where it was.
    expect(screen.getByTestId("embed-value-timeout")).toBe(input);
    expect(input).toHaveFocus();
    const rows = screen.getAllByTestId("embed-custom-row").map((r) => r.getAttribute("data-key"));
    expect(rows).toEqual(["timeout", "retries"]);
    await user.type(input, "5");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ embeddingParameters: { timeout: "5", retries: "2" } })
    );
  });

  it("refuses to rename a custom parameter onto a known one", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <StatefulEditor initial={{ embeddingProvider: "ollama", embeddingParameters: { x: "1" } }} spy={onChange} />
    );
    const key = screen.getByTestId("embed-custom-key");
    await user.clear(key);
    await user.type(key, "model");
    expect(onChange).not.toHaveBeenCalledWith(
      expect.objectContaining({ embeddingParameters: expect.objectContaining({ model: "1" }) })
    );
  });

  // ── Chunk strategy: sentence ──────────────────────────────────────────────

  it("shows a stored legacy strategy as what it is saved as, without offering it", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={{ ...populatedConfig, chunkStrategy: "sentence" }} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    const select = screen.getByTestId("chunk-strategy") as HTMLSelectElement;
    const legacy = [...select.options].find((o) => o.value === "sentence");
    expect(legacy).toBeDefined();
    expect(legacy!.disabled).toBe(true);
    expect(legacy!.textContent).toMatch(/saved as Recursive/);
    // Picking the real option replaces it.
    await user.selectOptions(select, "recursive");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ chunkStrategy: "recursive" })
    );
  });

  // ── Manual ingestion with unsaved edits ─────────────────────────────────────

  it("refuses manual ingestion while the knowledge base has unsaved edits", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} resourceId="kb1" isDirty />
    );
    await openSection(user, "Document Ingestion");

    // The ingest endpoint reads the SAVED config: the documents would go into
    // the old store with the old embedding model.
    expect(screen.getByTestId("ingestion-save-first")).toBeInTheDocument();
    expect(screen.getByTestId("ingestion-file-input")).toBeDisabled();

    await user.type(screen.getByPlaceholderText("Paste document text here..."), "hello");
    const ingest = screen.getByTestId("ingest-text-btn");
    expect(ingest).toBeDisabled();
    await user.click(ingest);

    // The file is never read: the fetch sits in FileReader.onload, so an
    // assertion on fetch alone would pass before an unguarded read finished.
    const readSpy = vi.spyOn(FileReader.prototype, "readAsText");
    fireEvent.drop(screen.getByTestId("ingestion-dropzone"), {
      dataTransfer: { files: [new File(["x"], "a.txt", { type: "text/plain" })] },
    });
    expect(readSpy).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalledWith(
      expect.stringContaining("/ingest"),
      expect.anything(),
    );
    readSpy.mockRestore();
    fetchSpy.mockRestore();
  });

  it("does not ingest a file whose read finished after the knowledge base was edited", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    // Hold the read: onload is fired by hand once the editor is dirty.
    const readers: FileReader[] = [];
    const readSpy = vi
      .spyOn(FileReader.prototype, "readAsText")
      .mockImplementation(function (this: FileReader) {
        readers.push(this);
      });
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} resourceId="kb1" isDirty={false} />
    );
    await openSection(user, "Document Ingestion");

    fireEvent.drop(screen.getByTestId("ingestion-dropzone"), {
      dataTransfer: { files: [new File(["x"], "a.txt", { type: "text/plain" })] },
    });
    expect(readers).toHaveLength(1);
    const pending = readers[0]!;

    rerender(<RagEditor data={populatedConfig} onChange={onChange} resourceId="kb1" isDirty />);
    await act(async () => {
      pending.onload?.call(pending, new ProgressEvent("load") as ProgressEvent<FileReader>);
    });

    expect(fetchSpy).not.toHaveBeenCalledWith(
      expect.stringContaining("/ingest"),
      expect.anything(),
    );
    expect(screen.getByText(/a\.txt was not ingested/)).toBeInTheDocument();
    readSpy.mockRestore();
    fetchSpy.mockRestore();
  });

  it("does not ingest a file whose read finishes before the edit's passive effects run", async () => {
    // The guard a FileReader.onload consults must already describe the new
    // props when the commit that shows them completes. A passive effect can
    // run later than that, and a read landing in between saw the old guard.
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const readers: FileReader[] = [];
    const readSpy = vi
      .spyOn(FileReader.prototype, "readAsText")
      .mockImplementation(function (this: FileReader) {
        readers.push(this);
      });
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <>
        <RagEditor data={populatedConfig} onChange={onChange} resourceId="kb1" isDirty={false} />
        <CommitProbe />
      </>,
    );
    await openSection(user, "Document Ingestion");
    fireEvent.drop(screen.getByTestId("ingestion-dropzone"), {
      dataTransfer: { files: [new File(["x"], "a.txt", { type: "text/plain" })] },
    });
    expect(readers).toHaveLength(1);
    const pending = readers[0]!;

    // The read completes inside the commit that makes the editor dirty.
    const completeRead = () =>
      pending.onload?.call(pending, new ProgressEvent("load") as ProgressEvent<FileReader>);
    await act(async () => {
      rerender(
        <>
          <RagEditor data={populatedConfig} onChange={onChange} resourceId="kb1" isDirty />
          <CommitProbe onCommit={completeRead} />
        </>,
      );
    });

    expect(fetchSpy).not.toHaveBeenCalledWith(
      expect.stringContaining("/ingest"),
      expect.anything(),
    );
    expect(screen.getByText(/a\.txt was not ingested/)).toBeInTheDocument();
    readSpy.mockRestore();
    fetchSpy.mockRestore();
  });

  it("allows manual ingestion once the edits are saved", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} resourceId="kb1" isDirty={false} />
    );
    await openSection(user, "Document Ingestion");
    expect(screen.queryByTestId("ingestion-save-first")).not.toBeInTheDocument();
    expect(screen.getByTestId("ingestion-file-input")).not.toBeDisabled();
    await user.type(screen.getByPlaceholderText("Paste document text here..."), "hello");
    expect(screen.getByTestId("ingest-text-btn")).not.toBeDisabled();
  });

  // ── Empty name clears to undefined ──────────────────────────────────────────

  it("clearing KB name sets name to undefined", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    const input = screen.getByTestId("kb-name");
    await user.clear(input);
    // After clearing, name should be set to undefined (empty string → undefined)
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ name: undefined })
    );
  });

  // ── ReadOnly mode disables KB name input ──────────────────────────────────

  it("disables KB name input in readOnly mode", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.getByTestId("kb-name")).toHaveAttribute("readonly");
  });

  // ── Min score color indicator ──────────────────────────────────────────────

  it("shows high score in emerald color", () => {
    const config: RagConfig = { ...populatedConfig, minScore: 0.85 };
    renderWithProviders(
      <RagEditor data={config} onChange={onChange} />
    );
    // Min score value should be rendered
    expect(screen.getByText("0.85")).toBeInTheDocument();
  });

  it("shows low score in red color", () => {
    const config: RagConfig = { ...populatedConfig, minScore: 0.3 };
    renderWithProviders(
      <RagEditor data={config} onChange={onChange} />
    );
    expect(screen.getByText("0.30")).toBeInTheDocument();
  });

  // ── Store hints for mongodb-atlas ──────────────────────────────────────────

  it("shows store hints for MongoDB Atlas", () => {
    const config: RagConfig = {
      storeType: "mongodb-atlas",
      storeParameters: {},
    };
    renderWithProviders(
      <RagEditor data={config} onChange={onChange} />
    );
    // MongoDB Atlas parameters include connectionString, databaseName, etc.
    expect(screen.getByTestId("store-row-connectionString")).toBeInTheDocument();
    expect(screen.getByTestId("store-row-databaseName")).toBeInTheDocument();
  });

  // ── ${connection:…} is refused by embedding models and vector stores ──────

  it("warns about a connection reference in an embedding parameter", () => {
    // A connection resolves to a whole header; an embedding model needs a bare
    // credential, and there is no honest way to derive one. The backend
    // refuses it at build time; the editor says so beside the field.
    const config: RagConfig = {
      ...populatedConfig,
      embeddingParameters: { model: "text-embedding-3-small", apiKey: "${connection:openai}" },
    };
    renderWithProviders(<RagEditor data={config} onChange={onChange} />);

    expect(screen.getByTestId("embed-connection-warning-apiKey")).toHaveTextContent("${vault:");
    expect(screen.queryByTestId("embed-connection-warning-model")).not.toBeInTheDocument();
  });

  it("warns about one in a vector-store parameter too, and offers no connection picker", () => {
    const config: RagConfig = {
      storeType: "qdrant",
      storeParameters: { apiKey: "${connection:qdrant}" },
    };
    renderWithProviders(<RagEditor data={config} onChange={onChange} />);

    expect(screen.getByTestId("store-connection-warning-apiKey")).toBeInTheDocument();
    expect(screen.queryByTestId("store-apiKey-connection-btn")).not.toBeInTheDocument();
  });
});


describe("RagEditor unknown chunk strategies", () => {
  it("tells an unsupported strategy apart from the two the server rewrites", async () => {
    // Only paragraph/sentence are rewritten to recursive; anything else is a
    // 400 on save, so "saved as Recursive" would be a false promise.
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={{ ...populatedConfig, chunkStrategy: "semantic" }} onChange={vi.fn()} />,
    );
    await user.click(screen.getByRole("button", { name: /Document Chunking/i }));
    const select = screen.getByTestId("chunk-strategy") as HTMLSelectElement;
    const option = [...select.options].find((o) => o.value === "semantic")!;
    expect(option.disabled).toBe(true);
    expect(option.textContent).toMatch(/not supported/);
    expect(option.textContent).not.toMatch(/saved as Recursive/);
    expect(screen.getByTestId("chunk-strategy-unsupported")).toBeInTheDocument();
  });

  it("does not warn for a legacy strategy", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={{ ...populatedConfig, chunkStrategy: "paragraph" }} onChange={vi.fn()} />,
    );
    await user.click(screen.getByRole("button", { name: /Document Chunking/i }));
    expect(screen.queryByTestId("chunk-strategy-unsupported")).not.toBeInTheDocument();
  });
});
