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

  it("switches a source between crawling and files, once the loss is confirmed", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(screen.getByTestId("ingestion-source-0-type-web"));

    // A saved file source that stops being one loses its files and vectors on
    // save (discardRemovedSources) — the same loss removing it asks about.
    expect(await screen.findByText("Turn this into a website source?")).toBeInTheDocument();
    expect(screen.getByTestId("ingestion-source-0-dropzone")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Switch to website" }));

    // The crawl fields come back, and the drop zone goes — one source is one
    // kind of source at a time.
    expect(await screen.findByTestId("ingestion-source-0-start-url")).toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-dropzone")).not.toBeInTheDocument();
  });

  it("keeps a file source when the switch to website is cancelled, by mouse or by arrow key", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(screen.getByTestId("ingestion-source-0-type-web"));
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(screen.getByTestId("ingestion-source-0-dropzone")).toBeInTheDocument();

    // The radiogroup's arrow keys took the same unguarded path.
    fireEvent.keyDown(screen.getByRole("radiogroup", { name: "Source" }), { key: "ArrowRight" });
    expect(await screen.findByText("Turn this into a website source?")).toBeInTheDocument();
    expect(screen.getByTestId("ingestion-source-0-dropzone")).toBeInTheDocument();
  });

  it("says the file list failed to load instead of claiming there are no files", async () => {
    let calls = 0;
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/files", () => {
        calls++;
        return HttpResponse.json({ message: "store unavailable" }, { status: 500 });
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    expect(await screen.findByTestId("ingestion-source-0-files-error")).toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-files-empty")).not.toBeInTheDocument();

    const before = calls;
    await user.click(screen.getByTestId("ingestion-source-0-files-reload"));
    await waitFor(() => expect(calls).toBeGreaterThan(before));
  });

  it("reloads the file list when a run is started and when it finishes", async () => {
    let fileListCalls = 0;
    let running = false;
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/files", () => {
        fileListCalls++;
        return HttpResponse.json([
          {
            fileId: "bbbb",
            fileName: "fresh.pdf",
            mimeType: "application/pdf",
            sizeBytes: 2048,
            contentHash: "b2",
            uploadedAt: "2026-09-18T09:13:00Z",
            indexState: running ? "NOT_INDEXED" : fileListCalls > 1 ? "INDEXED" : "NOT_INDEXED",
          },
        ]);
      }),
      http.get("*/ragstore/rags/:id/sources/:sourceId/runs", () =>
        HttpResponse.json(
          running
            ? [{ runId: "r1", sourceId: "src-files", status: "RUNNING", startedAt: "2026-09-18T09:14:00Z" }]
            : [],
        ),
      ),
      http.post("*/ragstore/rags/:id/sources/:sourceId/run", () => {
        running = true;
        return HttpResponse.json({ status: "started" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-run-from-files"));
    // Starting a run invalidates the list…
    await waitFor(() => expect(fileListCalls).toBeGreaterThanOrEqual(2));
    await screen.findByTestId("ingestion-source-0-running");

    // …and so does the run finishing, which happens long after the request
    // that started it returned. The runs query polls every 3 s.
    const beforeFinish = fileListCalls;
    running = false;
    await waitFor(() => expect(fileListCalls).toBeGreaterThan(beforeFinish), { timeout: 6000 });
  }, 15000);

  it("reloads the file list after a purge", async () => {
    let fileListCalls = 0;
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/files", () => {
        fileListCalls++;
        return HttpResponse.json([]);
      }),
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);
    await screen.findByTestId("ingestion-source-0-files-empty");
    const before = fileListCalls;

    await user.click(screen.getByTestId("ingestion-source-0-purge"));
    await user.click(await screen.findByRole("button", { name: "Purge" }));

    await waitFor(() => expect(fileListCalls).toBeGreaterThan(before));
  });

  it("does not offer a run from the file banner when the source is disabled", async () => {
    server.use(
      http.get("*/ragstore/rags/:id", ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.get("includePreviousVersions")) return;
        return HttpResponse.json({
          ...UPLOAD_SOURCE_CONFIG,
          sources: [{ ...UPLOAD_SOURCE_CONFIG.sources[0], enabled: false }],
        });
      }),
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
    );

    const user = userEvent.setup();
    renderRagPage();
    await openTheSource(user);

    expect(await screen.findByTestId("ingestion-source-0-awaiting-run")).toBeInTheDocument();
    // The card's own Run button is disabled for a disabled source; the banner
    // offered the same run, enabled, and the backend refused it.
    expect(screen.queryByTestId("ingestion-source-0-run-from-files")).not.toBeInTheDocument();
    expect(screen.getByTestId("ingestion-source-0-run")).toBeDisabled();
  });

  it("reloads the file list after a failed upload is retried", async () => {
    let attempts = 0;
    let fileListCalls = 0;
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/files", () => {
        fileListCalls++;
        return HttpResponse.json([]);
      }),
      http.post("*/ragstore/rags/:id/sources/:sourceId/files", async ({ request }) => {
        attempts++;
        const form = await request.formData();
        const file = form.get("files") as File;
        if (attempts === 1) return HttpResponse.json({ message: "busy" }, { status: 503 });
        return HttpResponse.json({
          stored: [
            {
              fileId: "cc33",
              fileName: "notes.md",
              mimeType: "text/markdown",
              sizeBytes: file.size,
              contentHash: "e1",
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
      dataTransfer: { files: [new File(["# notes"], "notes.md", { type: "text/markdown" })] },
    });
    const retry = await screen.findByTestId("ingestion-source-0-upload-retry");
    // The first load and the refetch that ends the dropped batch can land after
    // the retry button appears; count from after both, so only the retry's own
    // refetch can satisfy the assertion below.
    await waitFor(() => expect(fileListCalls).toBeGreaterThanOrEqual(2));
    const before = fileListCalls;

    await user.click(retry);

    await waitFor(() => expect(attempts).toBe(2));
    // The retried file is stored; the list must be asked again to show it.
    await waitFor(() => expect(fileListCalls).toBeGreaterThan(before));
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
