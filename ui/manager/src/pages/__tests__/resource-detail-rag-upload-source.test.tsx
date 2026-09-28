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

/**
 * The headers of the first part of a multipart upload.
 *
 * Only the headers, and read from the raw body rather than through
 * `request.formData()`. Node 24's fetch (undici 7) brand-checks `Blob` and
 * `File` where Node 22's accepted anything shaped like one, and the jsdom
 * environment replaces the global `File` with its own. So in this environment a
 * dropped file reaches the handler without its bytes, and `formData()` fails
 * its own check on the `File` it builds (an ERR_ASSERTION inside undici). What
 * survives on every Node is the part's field name and content type — and in a
 * browser, where there is only one `File`, none of this applies.
 */
async function firstUploadedPart(request: Request): Promise<{ field?: string; type?: string }> {
  const contentType = request.headers.get("content-type") ?? "";
  const boundary = /boundary="?([^";]+)"?/.exec(contentType)?.[1];
  if (!boundary) throw new Error(`not a multipart upload: ${contentType}`);
  const part = (await request.text()).split(`--${boundary}`)[1] ?? "";
  const headers = part.slice(0, part.indexOf("\r\n\r\n"));
  return {
    field: /content-disposition:[^\r\n]*\bname="([^"]*)"/i.exec(headers)?.[1],
    type: /content-type:\s*([^\r\n;]+)/i.exec(headers)?.[1]?.trim(),
  };
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
    const uploaded: { field?: string; type?: string }[] = [];
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/files", async ({ request }) => {
        // Not the file name: jsdom's XMLHttpRequest drops a multipart part's
        // filename, so asserting on it would be asserting on the test
        // environment rather than on the upload. Not the bytes either - see
        // firstUploadedPart.
        uploaded.push(await firstUploadedPart(request));
        return HttpResponse.json({
          stored: [
            {
              fileId: "aa11bb22cc33dd44ee55ff6677889900",
              fileName: "notes.md",
              mimeType: "text/markdown",
              sizeBytes: 7,
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

    await waitFor(() => expect(uploaded).toEqual([{ field: "files", type: "text/markdown" }]));
    expect(await screen.findByTestId("ingestion-source-0-upload-done")).toBeInTheDocument();
  });

  it("shows the server's reason when a file is refused, and keeps the others", async () => {
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/files", async ({ request }) => {
        // Matched on the part's content type, for the same reasons as above.
        if ((await firstUploadedPart(request)).type === "application/pdf") {
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
              sizeBytes: 5,
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

  it("says which files the knowledge base actually answers from", async () => {
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/files", () =>
        HttpResponse.json([
          {
            fileId: "aaaa",
            fileName: "indexed.pdf",
            mimeType: "application/pdf",
            sizeBytes: 1024,
            contentHash: "a1",
            uploadedAt: "2026-09-18T09:12:00Z",
            indexState: "INDEXED",
          },
          {
            fileId: "bbbb",
            fileName: "fresh.pdf",
            mimeType: "application/pdf",
            sizeBytes: 2048,
            contentHash: "b2",
            uploadedAt: "2026-09-18T09:13:00Z",
            indexState: "NOT_INDEXED",
          },
        ]),
      ),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    // Without this, a file that was uploaded and one that is in the knowledge
    // base look identical, and the only way to tell is to run the source and
    // compare counters.
    const list = await screen.findByTestId("ingestion-source-0-file-list");
    expect(within(list).getByTestId("file-state-indexed")).toBeInTheDocument();
    expect(within(list).getByTestId("file-state-not-indexed")).toBeInTheDocument();
    expect(await screen.findByTestId("ingestion-source-0-awaiting-run")).toBeInTheDocument();
  });

  it("offers a run when files are waiting to be indexed", async () => {
    let runStarted = false;
    server.use(
      // A file that is stored and not yet indexed, which is what the prompt to
      // run is about.
      http.get("*/ragstore/rags/:id/sources/:sourceId/files", () =>
        HttpResponse.json([
          {
            fileId: "bbbb",
            fileName: "fresh.pdf",
            mimeType: "application/pdf",
            sizeBytes: 2048,
            contentHash: "b2",
            uploadedAt: "2026-09-18T09:13:00Z",
            indexState: "NOT_INDEXED",
          },
        ]),
      ),
      http.post("*/ragstore/rags/:id/sources/:sourceId/run", () => {
        runStarted = true;
        return HttpResponse.json({ status: "started" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-run-from-files"));

    // Uploading and running are two steps, and the panel is where the operator
    // finds out that the second one is still owed.
    await waitFor(() => expect(runStarted).toBe(true));
  });

  it("keeps the server's warning when the chunks could not be removed", async () => {
    server.use(
      http.delete("*/ragstore/rags/:id/sources/:sourceId/files/:fileId", () =>
        HttpResponse.json({
          status: "deleted",
          fileId: "f1",
          warning:
            "The file is gone, but this knowledge base's vector store cannot delete by metadata, so the text it produced is still retrievable.",
        }),
      ),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-file-delete"));
    await user.click(await screen.findByRole("button", { name: /^delete$/i }));

    // The dialog promised that agents stop answering from it immediately.
    // Swallowing the one response that says otherwise would leave the operator
    // believing the promise.
    expect(
      await screen.findByTestId("ingestion-source-0-file-delete-warning"),
    ).toHaveTextContent(/still retrievable/i);
  });

  it("confirms before removing a source, because its files go with it", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: /ingestion sources/i }));

    await user.click(await screen.findByTestId("ingestion-source-0-remove"));

    // One unconfirmed click used to delete the only copy of every document the
    // source held.
    expect(screen.getByTestId("ingestion-source-0")).toBeInTheDocument();
    expect(await screen.findByText(/cannot be undone/i)).toBeInTheDocument();
  });
});
