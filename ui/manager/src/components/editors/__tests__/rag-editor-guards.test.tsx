import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { RagEditor, type RagConfig } from "@/components/editors/rag-editor";

const config: RagConfig = {
  name: "docs",
  embeddingProvider: "openai",
  embeddingParameters: { model: "text-embedding-3-small" },
  chunkSize: 512,
  chunkOverlap: 64,
};

async function openIngestion(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: /Document Ingestion/i }));
}

async function ingestText(user: ReturnType<typeof userEvent.setup>) {
  await openIngestion(user);
  await user.type(screen.getByPlaceholderText("Paste document text here..."), "hello");
  await user.click(screen.getByTestId("ingest-text-btn"));
}

describe("RagEditor — ingestion robustness", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => vi.useRealTimers());

  it("shows what the server said when an upload is refused", async () => {
    server.use(
      http.post("*/ragstore/rags/kb1/ingest", () =>
        HttpResponse.json({ message: "Document too large for this store" }, { status: 413 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RagEditor data={config} onChange={vi.fn()} resourceId="kb1" version={1} />);
    await ingestText(user);
    expect(await screen.findByText(/HTTP 413: Document too large for this store/)).toBeInTheDocument();
    expect(screen.queryByText(/upload error/i)).not.toBeInTheDocument();
  });

  it("does not call an ingestion failed because one status check failed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(
      http.post("*/ragstore/rags/kb1/ingest", () => HttpResponse.json({ ingestionId: "ing1" })),
    );
    let calls = 0;
    server.use(
      http.get("*/ragstore/rags/kb1/ingestion/ing1/status", () => {
        calls++;
        return calls === 2
          ? new HttpResponse(null, { status: 502 })
          : HttpResponse.json({ status: calls > 2 ? "completed" : "processing" });
      }),
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderWithProviders(<RagEditor data={config} onChange={vi.fn()} resourceId="kb1" version={1} />);
    await ingestText(user);
    for (let i = 0; i < 6; i++) await vi.advanceTimersByTimeAsync(4_000);
    expect(await screen.findByText("Completed")).toBeInTheDocument();
    expect(screen.queryByText(/could not check the status/i)).not.toBeInTheDocument();
  });

  it("does not read a file over the size cap", async () => {
    const readSpy = vi.spyOn(FileReader.prototype, "readAsText");
    const user = userEvent.setup();
    renderWithProviders(<RagEditor data={config} onChange={vi.fn()} resourceId="kb1" version={1} />);
    await openIngestion(user);
    const big = new File(["x"], "huge.txt", { type: "text/plain" });
    Object.defineProperty(big, "size", { value: 50 * 1024 * 1024 });
    fireEvent.drop(screen.getByTestId("ingestion-dropzone"), { dataTransfer: { files: [big] } });
    expect(readSpy).not.toHaveBeenCalled();
    expect(await screen.findByText(/huge\.txt is larger than 10 MB/)).toBeInTheDocument();
    readSpy.mockRestore();
  });
});

describe("RagEditor — stale vectors warning", () => {
  it("warns when the embedding model changes on a knowledge base that has ingested documents", async () => {
    server.use(
      http.post("*/ragstore/rags/kb1/ingest", () => HttpResponse.json({ ingestionId: "ing1" })),
      http.get("*/ragstore/rags/kb1/ingestion/ing1/status", () => HttpResponse.json({ status: "completed" })),
    );
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <RagEditor data={config} onChange={vi.fn()} resourceId="kb1" version={1} />,
    );
    expect(screen.queryByTestId("rag-reembed-warning")).not.toBeInTheDocument();
    await ingestText(user);
    await screen.findByText("Completed");

    const changed: RagConfig = { ...config, embeddingParameters: { model: "text-embedding-3-large" } };
    rerender(<RagEditor data={changed} onChange={vi.fn()} resourceId="kb1" version={1} />);
    expect(screen.getByTestId("rag-reembed-warning")).toBeInTheDocument();
  });

  it("stays quiet for a knowledge base with no documents", () => {
    const { rerender } = renderWithProviders(
      <RagEditor data={config} onChange={vi.fn()} resourceId="kb1" version={1} />,
    );
    rerender(
      <RagEditor
        data={{ ...config, embeddingProvider: "ollama" }}
        onChange={vi.fn()}
        resourceId="kb1"
        version={1}
      />,
    );
    expect(screen.queryByTestId("rag-reembed-warning")).not.toBeInTheDocument();
  });
});
