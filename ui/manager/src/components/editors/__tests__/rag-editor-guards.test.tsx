import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
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
  await user.click(screen.getByTestId("rag-ingestion-section"));
}

async function ingestText(user: ReturnType<typeof userEvent.setup>) {
  await openIngestion(user);
  await user.type(screen.getByTestId("ingest-text-input"), "hello");
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
    // The server's own words travel in the status, not a generic "upload error".
    // The status element exists (as "uploading") before the refusal arrives, so wait for its content.
    await waitFor(() =>
      expect(screen.getByTestId("ingestion-status").getAttribute("data-status")).toContain(
        "HTTP 413: Document too large for this store",
      ),
    );
    expect(screen.getByTestId("ingestion-status")).not.toHaveAttribute("data-status", "failed: upload error");
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
    await waitFor(() => expect(screen.getByTestId("ingestion-status")).toHaveAttribute("data-status", "completed"));
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
    expect(await screen.findByTestId("ingestion-status")).toHaveAttribute("data-status", "failed: too large huge.txt");
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
    await waitFor(() => expect(screen.getByTestId("ingestion-status")).toHaveAttribute("data-status", "completed"));

    const changed: RagConfig = { ...config, embeddingParameters: { model: "text-embedding-3-large" } };
    rerender(<RagEditor data={changed} onChange={vi.fn()} resourceId="kb1" version={1} />);
    expect(screen.getByTestId("rag-reembed-warning")).toBeInTheDocument();
  });

  it("warns conditionally when a saved knowledge base shows no documents: a manual ingest leaves no history", () => {
    const { rerender } = renderWithProviders(
      <RagEditor data={config} onChange={vi.fn()} resourceId="kb1" version={1} />,
    );
    expect(screen.queryByTestId("rag-reembed-warning-unknown")).not.toBeInTheDocument();
    rerender(
      <RagEditor
        data={{ ...config, embeddingProvider: "ollama" }}
        onChange={vi.fn()}
        resourceId="kb1"
        version={1}
      />,
    );
    // Not the definite warning — the page has no evidence of documents — but not silence either.
    expect(screen.queryByTestId("rag-reembed-warning")).not.toBeInTheDocument();
    expect(screen.getByTestId("rag-reembed-warning-unknown")).toBeInTheDocument();
  });

  it("stays quiet for a knowledge base that was never saved", () => {
    const { rerender } = renderWithProviders(<RagEditor data={config} onChange={vi.fn()} />);
    rerender(<RagEditor data={{ ...config, embeddingProvider: "ollama" }} onChange={vi.fn()} />);
    expect(screen.queryByTestId("rag-reembed-warning")).not.toBeInTheDocument();
    expect(screen.queryByTestId("rag-reembed-warning-unknown")).not.toBeInTheDocument();
  });
});
