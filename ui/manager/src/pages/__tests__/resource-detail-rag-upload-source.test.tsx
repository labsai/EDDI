import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ResourceDetailPage } from "@/pages/resource-detail";

/**
 * A knowledge base whose source takes uploaded files.
 *
 * Its own RAG config rather than a second source on the shared fixture: the
 * sources panel is addressed by index, and adding one there renumbers every
 * assertion in the crawl tests.
 */
const UPLOAD_SOURCE_CONFIG = {
  name: "handbooks",
  embeddingProvider: "openai",
  embeddingParameters: { model: "text-embedding-3-small", apiKey: "${vault:openai-key}" },
  storeType: "pgvector",
  storeParameters: { host: "localhost", port: "5432", database: "eddi", table: "embeddings" },
  chunkStrategy: "recursive",
  chunkSize: 512,
  chunkOverlap: 64,
  maxResults: 5,
  minScore: 0.6,
  sources: [
    {
      id: "src-files",
      name: "handbooks",
      type: "upload",
      enabled: true,
      upload: { maxFiles: 500, maxFileBytes: 26214400, maxTotalBytes: 524288000 },
    },
  ],
};

function renderRagPage(id = "res1") {
  return renderPage(
    `/manage/resources/rag/${id}`,
    <ResourceDetailPage />,
    "/manage/resources/:type/:id",
  );
}

async function openTheSource(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());
  await user.click(screen.getByRole("button", { name: /ingestion sources/i }));
  await waitFor(() => expect(screen.getByTestId("ingestion-source-0")).toBeInTheDocument());
  await user.click(screen.getByTestId("ingestion-source-0-toggle"));
}

describe("RAG upload source", () => {
  beforeEach(() => {
    server.use(
      http.get("*/ragstore/rags/:id", ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("includePreviousVersions")) return;
        return HttpResponse.json(UPLOAD_SOURCE_CONFIG);
      }),
    );
  });

  it("shows the file panel instead of the crawl fields", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    expect(await screen.findByTestId("ingestion-source-0-dropzone")).toBeInTheDocument();
    // A crawl's settings would be meaningless here, and leaving them on screen
    // invites somebody to fill them in and wonder why nothing uses them.
    expect(screen.queryByTestId("ingestion-source-0-start-url")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-max-pages")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-exclude-patterns")).not.toBeInTheDocument();
  });

  it("lists the files the source already holds", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    const list = await screen.findByTestId("ingestion-source-0-file-list");
    expect(within(list).getByText("employee-handbook.pdf")).toBeInTheDocument();
    expect(within(list).getByText("1.0 MB")).toBeInTheDocument();
  });

  it("uploads a dropped file and refreshes the list", async () => {
    const uploaded: string[] = [];
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/files", async ({ request }) => {
        const form = await request.formData();
        const file = form.get("files") as File;
        // The body, not the name: jsdom's XMLHttpRequest drops a multipart
        // part's filename, so asserting on it here would be asserting on the
        // test environment rather than on the upload.
        uploaded.push(await file.text());
        return HttpResponse.json({
          stored: [
            {
              fileId: "aa11bb22cc33dd44ee55ff6677889900",
              fileName: "notes.md",
              mimeType: "text/markdown",
              sizeBytes: file.size,
              contentHash: "d4e5f6",
              uploadedAt: "2026-09-18T09:20:00Z",
            },
          ],
          rejected: [],
        });
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    const dropzone = await screen.findByTestId("ingestion-source-0-dropzone");
    const file = new File(["# notes"], "notes.md", { type: "text/markdown" });
    fireEvent.drop(dropzone, { dataTransfer: { files: [file] } });

    await waitFor(() => expect(uploaded).toEqual(["# notes"]));
    expect(await screen.findByTestId("ingestion-source-0-upload-done")).toBeInTheDocument();
  });

  it("shows the server's reason when a file is refused, and keeps the others", async () => {
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/files", async ({ request }) => {
        const form = await request.formData();
        const file = form.get("files") as File;
        // Matched on the content for the same reason as above.
        if ((await file.text()).startsWith("%PDF")) {
          return HttpResponse.json(
            {
              stored: [],
              rejected: [{ fileName: "locked.pdf", reason: "This PDF is encrypted." }],
            },
            { status: 400 },
          );
        }
        return HttpResponse.json({
          stored: [
            {
              fileId: "bb22",
              fileName: "readme.txt",
              mimeType: "text/plain",
              sizeBytes: file.size,
              contentHash: "c3",
              uploadedAt: "2026-09-18T09:20:00Z",
            },
          ],
          rejected: [],
        });
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    const dropzone = await screen.findByTestId("ingestion-source-0-dropzone");
    fireEvent.drop(dropzone, {
      dataTransfer: {
        files: [
          new File(["%PDF-1.4"], "locked.pdf", { type: "application/pdf" }),
          new File(["hello"], "readme.txt", { type: "text/plain" }),
        ],
      },
    });

    // The refusal is the server's sentence, not a generic failure — it is the
    // only thing that tells the operator what to do about it.
    expect(await screen.findByText("This PDF is encrypted.")).toBeInTheDocument();
    // And the other file in the same drop still went.
    await waitFor(() =>
      expect(screen.getByTestId("ingestion-source-0-upload-done")).toBeInTheDocument(),
    );
  });

  it("deletes a file after confirming", async () => {
    let deletedId: string | null = null;
    server.use(
      http.delete("*/ragstore/rags/:id/sources/:sourceId/files/:fileId", ({ params }) => {
        deletedId = String(params.fileId);
        return HttpResponse.json({ status: "deleted", fileId: params.fileId });
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-file-delete"));
    await user.click(await screen.findByRole("button", { name: /^delete$/i }));

    await waitFor(() => expect(deletedId).toBe("3f2a91c4e5b6d7089a1b2c3d4e5f6071"));
  });

  it("switches a source between crawling and files", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(screen.getByTestId("ingestion-source-0-type-web"));

    // The crawl fields come back, and the drop zone goes — one source is one
    // kind of source at a time.
    expect(await screen.findByTestId("ingestion-source-0-start-url")).toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-dropzone")).not.toBeInTheDocument();
  });
});
