import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { createTestQueryClient, renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ResourceDetailPage } from "@/pages/resource-detail";

/**
 * What the Manager shows about ingestion while and after it runs: live progress,
 * the full run record, the schedule's real state, what the knowledge base holds,
 * and a test retrieval. Fixtures follow the backend's response shapes
 * (RestRagIngestion).
 */

function renderRagPage(id = "res1") {
  return renderPage(`/manage/resources/rag/${id}`, <ResourceDetailPage />, "/manage/resources/:type/:id");
}

async function openFirstSource(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());
  await user.click(screen.getByRole("button", { name: /ingestion sources/i }));
  await waitFor(() => expect(screen.getByTestId("ingestion-source-0")).toBeInTheDocument());
  await user.click(screen.getByTestId("ingestion-source-0-toggle"));
}

const COMPLETED_RUN = {
  runId: "run-1",
  sourceId: "src-1",
  status: "COMPLETED",
  startedAt: "2026-10-14T02:00:00Z",
  finishedAt: "2026-10-14T02:04:12Z",
  documentsSeen: 40,
  documentsIngested: 3,
  documentsUnchanged: 36,
  documentsFailed: 1,
  documentsTombstoned: 1,
  segmentsStored: 57,
  costUsd: 0,
  error: null,
  documentsSkipped: 0,
  stopReason: "PAGE_LIMIT",
  trigger: "SCHEDULED",
};

describe("RAG ingestion — while and after a run", () => {
  it("shows a running run's counters and how far it has got", async () => {
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/runs", () =>
        HttpResponse.json([
          {
            ...COMPLETED_RUN,
            runId: "run-2",
            status: "RUNNING",
            startedAt: new Date(Date.now() - 65_000).toISOString(),
            finishedAt: null,
            documentsSeen: 10,
            documentsIngested: 4,
            documentsUnchanged: 6,
            documentsFailed: 0,
            stopReason: null,
            trigger: "MANUAL",
          },
          COMPLETED_RUN,
        ]),
      ),
    );
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const progress = await screen.findByTestId("ingestion-source-0-progress");
    // The last run that covered the source saw 40 pages: the estimate for this one.
    expect(within(progress).getByTestId("ingestion-source-0-progress-summary")).toHaveTextContent(
      "10 of about 40 documents looked at",
    );
    expect(within(progress).getByTestId("ingestion-source-0-progress-bar")).toHaveStyle({ width: "25%" });
    expect(within(progress).getByText("Manual")).toBeInTheDocument();
    expect(within(progress).getByTestId("ingestion-source-0-progress-elapsed")).toHaveTextContent(
      "of a 10 min budget",
    );
  });

  it("opens a past run to everything it recorded, including why it stopped", async () => {
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/runs", () => HttpResponse.json([COMPLETED_RUN])),
    );
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const row = await screen.findByTestId("ingestion-source-0-run-run-1");
    // A run that lost a document is not summarised as a clean success.
    expect(row).toHaveTextContent("1 failed");
    expect(within(row).getByText("Scheduled")).toBeInTheDocument();

    await user.click(screen.getByTestId("ingestion-source-0-run-run-1-toggle"));
    const details = screen.getByTestId("ingestion-source-0-run-run-1-details");
    expect(details).toHaveTextContent("57");
    expect(screen.getByTestId("ingestion-source-0-run-run-1-stop-reason")).toHaveTextContent(
      "Stopped at the page limit",
    );
  });

  it("says what the server answered when a run cannot start", async () => {
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/runs", () => HttpResponse.json([])),
      http.post("*/ragstore/rags/:id/sources/:sourceId/run", () =>
        HttpResponse.json({ error: "This source is disabled. Enable it to run it." }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-run"));

    expect(await screen.findByTestId("ingestion-source-0-run-error")).toHaveTextContent(
      "This source is disabled. Enable it to run it.",
    );
  });
});

describe("RAG ingestion — schedule", () => {
  it("shows when the schedule fires next and that its last fires failed", async () => {
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/schedule", () =>
        HttpResponse.json({
          scheduled: true,
          scheduleId: "sched-rag-1",
          cron: "0 2 * * *",
          timeZone: "UTC",
          enabled: true,
          nextFire: new Date(Date.now() + 3 * 3600_000).toISOString(),
          lastFired: "2026-10-15T02:00:00Z",
          fireStatus: "FAILED",
          failCount: 2,
          recentFires: [
            {
              fireTime: "2026-10-15T02:00:00Z",
              status: "FAILED",
              errorMessage: "Embedding provider returned 401",
              attemptNumber: 2,
            },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const schedule = await screen.findByTestId("ingestion-source-0-schedule");
    expect(schedule).toHaveTextContent("in 3 hours");
    expect(screen.getByTestId("ingestion-source-0-schedule-failing")).toHaveTextContent("The last 2 fire(s) failed");
    expect(screen.getByTestId("ingestion-source-0-schedule-fires")).toHaveTextContent(
      "Embedding provider returned 401",
    );
  });

  it("warns when a saved cron produced no schedule", async () => {
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/schedule", () =>
        HttpResponse.json({ scheduled: false, recentFires: [] }),
      ),
    );
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    expect(await screen.findByTestId("ingestion-source-0-schedule-missing")).toBeInTheDocument();
  });

  it("checks a custom cron as it is typed, and shows when it would fire", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    const preset = await screen.findByTestId("ingestion-source-0-cron-preset");
    // The saved cron is a preset, so it is selected as one.
    expect(preset).toHaveValue("0 2 * * *");

    await user.selectOptions(preset, "__custom__");
    const input = screen.getByTestId("ingestion-source-0-cron");
    await user.clear(input);
    await user.type(input, "0 2 *");
    expect(await screen.findByTestId("ingestion-source-0-cron-error")).toHaveTextContent("exactly 5 fields");

    await user.type(input, " * *");
    expect(await screen.findByTestId("ingestion-source-0-cron-next")).toHaveTextContent("Next runs (your local time):");
  });
});

describe("RAG ingestion — what the knowledge base holds", () => {
  it("lists a source's documents, filters them, and opens one to its chunks", async () => {
    const user = userEvent.setup();
    renderRagPage();
    await openFirstSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-contents-toggle"));
    await screen.findByTestId("ingestion-source-0-doc-https://example.com/docs/intro");
    expect(screen.getByTestId("ingestion-source-0-contents-total")).toHaveTextContent("2");
    // A page the source dropped is shown as removed, not as live.
    expect(screen.getByTestId("ingestion-source-0-doc-https://example.com/docs/old-page")).toHaveTextContent(
      "Removed",
    );

    await user.type(screen.getByTestId("ingestion-source-0-contents-filter"), "intro");
    await waitFor(() =>
      expect(screen.queryByTestId("ingestion-source-0-doc-https://example.com/docs/old-page")).toBeNull(),
    );

    await user.click(screen.getByTestId("ingestion-source-0-doc-https://example.com/docs/intro-toggle"));
    const chunks = await screen.findByTestId("ingestion-source-0-doc-https://example.com/docs/intro-chunks");
    await waitFor(() => expect(chunks).toHaveTextContent("Chunk 1"));
    expect(chunks).toHaveTextContent("EDDI is a config-driven engine for conversational AI.");
    expect(chunks).toHaveTextContent("Chunk 2");
  });

  it("steps back to the last page when a refetch leaves fewer pages", async () => {
    let total = 30;
    server.use(
      http.get("*/ragstore/rags/:id/sources/:sourceId/documents", ({ request }) => {
        const offset = Number(new URL(request.url).searchParams.get("offset") ?? 0);
        const all = Array.from({ length: total }, (_, i) => ({
          documentId: `https://example.com/p${i}`,
          label: `https://example.com/p${i}`,
          url: `https://example.com/p${i}`,
          missedRuns: 0,
          tombstoned: false,
        }));
        return HttpResponse.json({ total, truncated: false, documents: all.slice(offset, offset + 25) });
      }),
    );
    const client = createTestQueryClient();
    const user = userEvent.setup();
    renderPage(`/manage/resources/rag/res1`, <ResourceDetailPage />, "/manage/resources/:type/:id", client);
    await openFirstSource(user);
    await user.click(await screen.findByTestId("ingestion-source-0-contents-toggle"));
    await user.click(await screen.findByTestId("ingestion-source-0-contents-next"));
    // Past the filter's debounce window: opening the list must not later snap the
    // page back to the first one.
    await new Promise((resolve) => setTimeout(resolve, 500));
    await screen.findByTestId("ingestion-source-0-doc-https://example.com/p29");

    // A run removed documents: only one page is left.
    total = 3;
    await client.invalidateQueries({ queryKey: ["ingestion", "documents"] });

    // The first page's cached copy (still 30 documents) shows until its refetch lands.
    await waitFor(() => {
      expect(screen.getByTestId("ingestion-source-0-contents-total")).toHaveTextContent(/^3$/);
      expect(screen.queryByTestId("ingestion-source-0-contents-next")).not.toBeInTheDocument();
    });
    expect(screen.getByTestId("ingestion-source-0-doc-https://example.com/p0")).toBeInTheDocument();
  });

  it("runs a test retrieval and shows each chunk with its score and origin", async () => {
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/ragstore/rags/:id/search", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          maxResults: 5,
          minScore: 0.6,
          results: [
            {
              index: 0,
              score: 0.873,
              text: "EDDI is a config-driven engine for conversational AI.",
              metadata: { title: "Introduction", url: "https://example.com/docs/intro" },
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /test retrieval/i }));
    await user.type(screen.getByTestId("retrieval-query"), "what is eddi");
    await user.selectOptions(screen.getByTestId("retrieval-source"), "src-1");
    await user.click(screen.getByTestId("retrieval-search"));

    const results = await screen.findByTestId("retrieval-results");
    expect(within(results).getByTestId("chunk-score")).toHaveTextContent("0.873");
    expect(within(results).getByText("Introduction")).toBeInTheDocument();
    expect(sent).toMatchObject({ query: "what is eddi", sourceId: "src-1" });
  });

  it("says why a test retrieval failed", async () => {
    server.use(
      http.post("*/ragstore/rags/:id/search", () =>
        HttpResponse.json(
          { error: "The embedding model or vector store failed: 401 invalid api key" },
          { status: 502 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderRagPage();
    await waitFor(() => expect(screen.getByTestId("rag-editor")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /test retrieval/i }));
    await user.type(screen.getByTestId("retrieval-query"), "hello");
    await user.click(screen.getByTestId("retrieval-search"));

    expect(await screen.findByTestId("retrieval-error")).toHaveTextContent("401 invalid api key");
  });
});
