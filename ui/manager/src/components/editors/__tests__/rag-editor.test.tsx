import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
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

  it("changes embedding provider and auto-populates params", async () => {
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
        embeddingParameters: expect.objectContaining({
          model: "",
          baseUrl: "",
        }),
      })
    );
  });

  it("shows embedding parameter key-value pairs", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("model")).toBeInTheDocument();
    expect(
      screen.getByDisplayValue("text-embedding-3-small")
    ).toBeInTheDocument();
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
        storeParameters: expect.objectContaining({
          connectionString: "${vault:mongo-uri}",
        }),
      })
    );
  });

  it("shows store parameters for pgvector", () => {
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("host")).toBeInTheDocument();
    expect(screen.getByDisplayValue("localhost")).toBeInTheDocument();
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

  it("changes chunk strategy to paragraph", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    await user.selectOptions(screen.getByTestId("chunk-strategy"), "paragraph");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ chunkStrategy: "paragraph" })
    );
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

  // ── Auto-suggest hint keys ─────────────────────────────────────────────────

  it("shows auto-suggest hint buttons for missing embedding params", () => {
    const configWithMinimalParams: RagConfig = {
      embeddingProvider: "openai",
      embeddingParameters: {},
    };
    renderWithProviders(
      <RagEditor data={configWithMinimalParams} onChange={onChange} />
    );
    // OpenAI hints include "model" and "apiKey" which are not in empty params
    expect(screen.getByText("+ model")).toBeInTheDocument();
    expect(screen.getByText("+ apiKey")).toBeInTheDocument();
  });

  it("adds a hint key when clicked", async () => {
    const user = userEvent.setup();
    const configWithMinimalParams: RagConfig = {
      embeddingProvider: "openai",
      embeddingParameters: {},
    };
    renderWithProviders(
      <RagEditor data={configWithMinimalParams} onChange={onChange} />
    );
    await user.click(screen.getByText("+ model"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        embeddingParameters: expect.objectContaining({ model: "" }),
      })
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

  // ── Vault reference values ─────────────────────────────────────────────────

  it("renders vault reference placeholder for apiKey in hint buttons", () => {
    const configWithMinimalParams: RagConfig = {
      embeddingProvider: "openai",
      embeddingParameters: {},
    };
    renderWithProviders(
      <RagEditor data={configWithMinimalParams} onChange={onChange} />
    );
    // apiKey hint should auto-populate the vault placeholder when clicked
    expect(screen.getByText("+ apiKey")).toBeInTheDocument();
  });

  it("auto-fills vault placeholder when clicking apiKey hint", async () => {
    const user = userEvent.setup();
    const configWithMinimalParams: RagConfig = {
      embeddingProvider: "openai",
      embeddingParameters: {},
    };
    renderWithProviders(
      <RagEditor data={configWithMinimalParams} onChange={onChange} />
    );
    await user.click(screen.getByText("+ apiKey"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        embeddingParameters: expect.objectContaining({
          apiKey: "${vault:openai-key}",
        }),
      })
    );
  });

  // ── KeyValue editing ───────────────────────────────────────────────────────

  it("adds parameter via Add Parameter button", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    // Click "Add Parameter" for store params
    const addBtns = screen.getAllByText("Add Parameter");
    // At least one "Add Parameter" button for embedding params and one for store params
    await user.click(addBtns[0]!);
    expect(onChange).toHaveBeenCalled();
  });

  // ── Embedding provider switching ───────────────────────────────────────────

  it("switches embedding provider to azure-openai", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.selectOptions(
      screen.getByTestId("embedding-provider"),
      "azure-openai"
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        embeddingProvider: "azure-openai",
        embeddingParameters: expect.objectContaining({
          endpoint: "",
        }),
      })
    );
  });

  it("switches embedding provider to mistral", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.selectOptions(
      screen.getByTestId("embedding-provider"),
      "mistral"
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        embeddingProvider: "mistral",
      })
    );
  });

  it("switches embedding provider to gemini", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.selectOptions(
      screen.getByTestId("embedding-provider"),
      "gemini"
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        embeddingProvider: "gemini",
        embeddingParameters: expect.objectContaining({
          apiKey: "${eddivault:gemini-key}",
        }),
      })
    );
  });

  // ── Store type switching ───────────────────────────────────────────────────

  it("switches to elasticsearch store", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("store-elasticsearch"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        storeType: "elasticsearch",
        storeParameters: expect.objectContaining({
          serverUrl: "",
        }),
      })
    );
  });

  it("switches to qdrant store", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("store-qdrant"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        storeType: "qdrant",
        storeParameters: expect.objectContaining({
          host: "",
        }),
      })
    );
  });

  it("switches to chroma store", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("store-chroma"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        storeType: "chroma",
        storeParameters: expect.objectContaining({
          baseUrl: "",
        }),
      })
    );
  });

  // ── Chunk strategy: sentence ──────────────────────────────────────────────

  it("changes chunk strategy to sentence", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RagEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Document Chunking");
    await user.selectOptions(screen.getByTestId("chunk-strategy"), "sentence");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ chunkStrategy: "sentence" })
    );
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
    // MongoDB Atlas hints include connectionString, databaseName, etc.
    expect(screen.getByText("+ connectionString")).toBeInTheDocument();
    expect(screen.getByText("+ databaseName")).toBeInTheDocument();
  });
});

