import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderRagPage(id = "res1") {
  return renderPage(
    `/manage/resources/rag/${id}`,
    <ResourceDetailPage />,
    "/manage/resources/:type/:id",
  );
}

/** Opens the Ingestion Sources section and expands the first source. */
async function openFirstSource(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());

  await user.click(screen.getByRole("button", { name: /ingestion sources/i }));
  await waitFor(() => expect(screen.getByTestId("ingestion-source-0")).toBeInTheDocument());

  await user.click(screen.getByTestId("ingestion-source-0-toggle"));
}

describe("RAG ingestion sources", () => {
  it("lists the knowledge base's sources", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /ingestion sources/i }));

    const source = await screen.findByTestId("ingestion-source-0");
    expect(within(source).getByText("public-docs")).toBeInTheDocument();
    // The cron is shown on the collapsed row, so a scheduled source is obvious
    // without opening it.
    expect(within(source).getByText("0 2 * * *")).toBeInTheDocument();
  });

  it("populates the crawl fields from the saved source", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    await waitFor(() => {
      expect((screen.getByTestId("ingestion-source-0-start-url") as HTMLInputElement).value).toBe(
        "https://example.com/docs/",
      );
    });
    expect((screen.getByTestId("ingestion-source-0-path-prefix") as HTMLInputElement).value).toBe(
      "/docs/",
    );
    expect((screen.getByTestId("ingestion-source-0-max-pages") as HTMLInputElement).value).toBe("200");
    expect(
      (screen.getByTestId("ingestion-source-0-exclude-patterns") as HTMLInputElement).value,
    ).toBe("*.pdf");
  });

  it("shows robots.txt as respected by default", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const respectRobots = await screen.findByTestId("ingestion-source-0-respect-robots");
    expect((respectRobots as HTMLInputElement).checked).toBe(true);
  });

  it("shows the run history for a saved source", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const runs = await screen.findByTestId("ingestion-source-0-runs");
    expect(within(runs).getByText(/3 ingested/)).toBeInTheDocument();
  });

  it("previews a source without embedding anything", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-preview"));

    const result = await screen.findByTestId("ingestion-source-0-preview-result");
    // The heading says plainly that nothing was written — a preview that looked
    // like a run would be worse than no preview.
    expect(within(result).getByText(/nothing was embedded/i)).toBeInTheDocument();
  });

  it("starts a run", async () => {
    const user = userEvent.setup();
    // Asserting on the button alone passes even if the click sends nothing: it is
    // enabled before the click and enabled after it. Record the request instead.
    // The handler pattern matches any id, so counting alone would still pass if the
    // UI addressed /sources/undefined/run?version=NaN. Record the URL.
    const runUrls: string[] = [];
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/run", ({ request }) => {
        runUrls.push(request.url);
        return HttpResponse.json({ status: "started", sourceId: "src-1" }, { status: 202 });
      }),
    );
    renderRagPage();
    await openFirstSource(user);

    const runButton = await screen.findByTestId("ingestion-source-0-run");
    await user.click(runButton);

    await waitFor(() => expect(runUrls).toHaveLength(1));
    expect(runUrls[0]).toMatch(/\/ragstore\/rags\/res1\/sources\/src-1\/run\?version=1$/);
    // The endpoint answers 202 and the history is refetched; the button stays
    // usable rather than leaving the operator guessing.
    await waitFor(() => expect(runButton).toBeEnabled());
  });

  it("refuses to run while the editor has unsaved changes", async () => {
    const user = userEvent.setup();
    let runRequests = 0;
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/run", () => {
        runRequests += 1;
        return HttpResponse.json({ status: "started", sourceId: "src-1" }, { status: 202 });
      }),
    );
    renderRagPage();
    await openFirstSource(user);

    // A run addresses the source by id and version, so the server would crawl the
    // saved configuration while the screen shows something else.
    const startUrl = await screen.findByTestId("ingestion-source-0-start-url");
    await user.type(startUrl, "/changed");

    expect(await screen.findByTestId("ingestion-source-0-save-before-run")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId("ingestion-source-0-run")).toBeDisabled(),
    );
    expect(screen.getByTestId("ingestion-source-0-preview")).toBeDisabled();
    expect(runRequests).toBe(0);
  });

  it("keeps the purge dialog open and reports the failure when purging fails", async () => {
    const user = userEvent.setup();
    server.use(
      http.delete("*/ragstore/rags/:id/sources/:sourceId/documents", () =>
        HttpResponse.json({ error: "nope" }, { status: 500 }),
      ),
    );
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-purge"));
    await user.click(await screen.findByRole("button", { name: /^purge$/i }));

    expect(await screen.findByTestId("ingestion-source-0-purge-error")).toBeInTheDocument();
    // Closing on a rejected request left the operator believing it had worked.
    expect(screen.getByText(/purge ingestion state\?/i)).toBeInTheDocument();
  });

  it("says why a preview produced nothing instead of showing an empty crawl", async () => {
    const user = userEvent.setup();
    // The endpoint answers 200 with outcome FAILED — a mistyped start URL, a site
    // that is down, a seed that 403s. Rendering the counters would tell the
    // operator their site is empty.
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/preview", () =>
        HttpResponse.json({
          runId: "preview",
          sourceId: "src-1",
          outcome: "FAILED",
          documentsSeen: 0,
          documentsIngested: 0,
          documentsUnchanged: 0,
          documentsFailed: 0,
          documentsTombstoned: 0,
          segmentsStored: 0,
          costUsd: 0,
          replaceUnsupported: false,
          tombstoningSkipped: false,
          message: "Seed URL must be http or https",
        }),
      ),
    );
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-preview"));

    const failure = await screen.findByTestId("ingestion-source-0-preview-failed");
    expect(failure).toHaveTextContent(/seed url must be http or https/i);
    expect(screen.queryByTestId("ingestion-source-0-preview-result")).not.toBeInTheDocument();
  });

  it("reports a preview request that fails outright", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("*/ragstore/rags/:id/sources/:sourceId/preview", () =>
        HttpResponse.json({ error: "nope" }, { status: 500 }),
      ),
    );
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-preview"));

    expect(await screen.findByTestId("ingestion-source-0-preview-error")).toBeInTheDocument();
  });

  it("does not offer Purge while a run is in flight", async () => {
    const user = userEvent.setup();
    // Purging deletes the RUNNING row, which is the only thing stopping a second
    // crawl into the same knowledge base.
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/runs", () =>
        HttpResponse.json([
          {
            runId: "run-1",
            sourceId: "src-1",
            status: "RUNNING",
            startedAt: "2026-09-20T10:00:00Z",
            documentsSeen: 0,
            documentsIngested: 0,
            documentsUnchanged: 0,
            documentsFailed: 0,
            documentsTombstoned: 0,
            segmentsStored: 0,
            costUsd: 0,
          },
        ]),
      ),
    );
    renderRagPage();
    await openFirstSource(user);

    await waitFor(() => expect(screen.getByTestId("ingestion-source-0-purge")).toBeDisabled());
    expect(screen.getByTestId("ingestion-source-0-run")).toBeDisabled();
    expect(await screen.findByTestId("ingestion-source-0-running-hint")).toBeInTheDocument();
  });

  it("does not offer Run for a disabled source", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    await user.click(screen.getByTestId("ingestion-source-0-enabled"));

    await waitFor(() => expect(screen.getByTestId("ingestion-source-0-run")).toBeDisabled());
    expect(await screen.findByTestId("ingestion-source-0-disabled-hint")).toBeInTheDocument();
  });

  it("keeps a second exclude pattern that is typed rather than pasted", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const field = await screen.findByTestId("ingestion-source-0-exclude-patterns");
    await user.clear(field);
    // Deriving the field's value from the parsed array ate the comma on the
    // keystroke that added it, so only the first pattern survived.
    await user.type(field, "*.pdf, **/changelog/**");

    expect(field).toHaveValue("*.pdf, **/changelog/**");
  });

  it("asks before purging, because the next run re-embeds everything", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-purge"));

    expect(await screen.findByText(/purge ingestion state\?/i)).toBeInTheDocument();
  });

  it("adds a new source with safe defaults", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: /ingestion sources/i }));

    await user.click(await screen.findByTestId("add-ingestion-source"));

    const added = await screen.findByTestId("ingestion-source-1");
    expect(added).toBeInTheDocument();
    // A new source cannot be run until the knowledge base is saved — it has no id
    // for the endpoints to address.
    expect(await screen.findByTestId("ingestion-source-1-save-first")).toBeInTheDocument();
    expect(
      (screen.getByTestId("ingestion-source-1-respect-robots") as HTMLInputElement).checked,
    ).toBe(true);
  });

  it("removes a source", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: /ingestion sources/i }));

    await user.click(await screen.findByTestId("ingestion-source-0-remove"));

    await waitFor(() => {
      expect(screen.queryByTestId("ingestion-source-0")).not.toBeInTheDocument();
    });
  });
});
