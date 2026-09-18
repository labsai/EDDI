import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
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
    renderRagPage();
    await openFirstSource(user);

    const runButton = await screen.findByTestId("ingestion-source-0-run");
    await user.click(runButton);

    // The endpoint answers 202 and the history is refetched; the button stays
    // usable rather than leaving the operator guessing.
    await waitFor(() => expect(runButton).toBeEnabled());
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
