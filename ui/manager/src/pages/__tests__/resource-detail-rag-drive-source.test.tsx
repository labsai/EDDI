import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@eddi/app-kit/test/test-utils";
import { MOCK_GOOGLE_DRIVE_KB_ID } from "@eddi/app-kit/test/mocks/handlers";
import { ResourceDetailPage } from "@/pages/resource-detail";

/**
 * A knowledge base fed by a Google Shared Drive, served by the shared mock for
 * its own id (`MOCK_GOOGLE_DRIVE_KB`), so the crawl fixture keeps its indexes.
 */
function renderRagPage(id: string) {
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

describe("RAG Google Drive source", () => {
  it("shows the drive's connection, id and scope instead of the crawl fields", async () => {
    const user = userEvent.setup();
    renderRagPage(MOCK_GOOGLE_DRIVE_KB_ID);
    await openTheSource(user);

    expect(await screen.findByTestId("ingestion-source-0-drive-connection")).toHaveValue("google-drive-sa");
    expect(screen.getByTestId("ingestion-source-0-drive-id")).toHaveValue("0AmockSharedDriveId");
    expect(screen.getByTestId("ingestion-source-0-type-google-drive")).toHaveAttribute("aria-checked", "true");
    expect(screen.getByTestId("ingestion-source-0-drive-audience")).toBeInTheDocument();
    // Neither the crawl's nor the upload's settings mean anything for a drive.
    expect(screen.queryByTestId("ingestion-source-0-start-url")).not.toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-dropzone")).not.toBeInTheDocument();
  });

  it("shows what the preview listed: counts, size and the types it skipped", async () => {
    const user = userEvent.setup();
    renderRagPage(MOCK_GOOGLE_DRIVE_KB_ID);
    await openTheSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-preview"));

    const summary = await screen.findByTestId("ingestion-source-0-preview-summary");
    expect(summary).toHaveTextContent("12 file(s) to read");
    expect(summary).toHaveTextContent("image/png");
  });

  it("turns a website source into a Drive source with an empty drive block to fill in", async () => {
    const user = userEvent.setup();
    renderRagPage("res1");
    await openTheSource(user);

    await user.click(await screen.findByTestId("ingestion-source-0-type-google-drive"));

    expect(await screen.findByTestId("ingestion-source-0-drive-connection")).toHaveValue("");
    expect(screen.getByTestId("ingestion-source-0-drive-include-subfolders")).toBeInTheDocument();
    expect(screen.queryByTestId("ingestion-source-0-start-url")).not.toBeInTheDocument();
  });
});
