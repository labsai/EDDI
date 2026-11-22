import { describe, it, expect, vi, beforeEach } from "vitest";
import { useState } from "react";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { DriveFolderScope } from "@/components/editors/drive-folder-scope";
import { listDriveFolders, type GoogleDriveSource } from "@/lib/api/ingestion-sources";

vi.mock("@/lib/api/ingestion-sources", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/ingestion-sources")>();
  return { ...actual, listDriveFolders: vi.fn() };
});

const listFolders = vi.mocked(listDriveFolders);

/** The scope editor over state it owns, as the source panel holds it. */
function Harness({
  initial,
  saved = true,
  unsaved = false,
  onState,
}: {
  initial: GoogleDriveSource;
  saved?: boolean;
  unsaved?: boolean;
  onState?: (drive: GoogleDriveSource) => void;
}) {
  const [drive, setDrive] = useState<GoogleDriveSource>(initial);
  return (
    <DriveFolderScope
      drive={drive}
      onChange={(patch) => {
        const next = { ...drive, ...patch };
        setDrive(next);
        onState?.(next);
      }}
      kbId={saved ? "kb1" : undefined}
      sourceId={saved ? "src1" : undefined}
      version={3}
      hasUnsavedChanges={unsaved}
      testId="s"
    />
  );
}

describe("DriveFolderScope", () => {
  beforeEach(() => {
    listFolders.mockReset();
  });

  it("adds a typed path as a folder to read, and another as one to leave out", async () => {
    const user = userEvent.setup();
    let state: GoogleDriveSource = {};
    renderWithProviders(<Harness initial={{}} onState={(drive) => (state = drive)} />);
    expect(screen.getByTestId("s-drive-scope-included")).toHaveTextContent("The whole drive");

    await user.type(screen.getByTestId("s-drive-scope-path"), "/Sales/Offers/");
    await user.click(screen.getByTestId("s-drive-scope-add-include"));
    await user.type(screen.getByTestId("s-drive-scope-path"), "Sales/Offers/Archive");
    await user.click(screen.getByTestId("s-drive-scope-add-exclude"));

    expect(state.folders).toEqual([{ path: "Sales/Offers" }]);
    expect(state.excludeFolders).toEqual([{ path: "Sales/Offers/Archive" }]);
  });

  it("shows the older folderIds and lets them be removed", async () => {
    const user = userEvent.setup();
    let state: GoogleDriveSource = {};
    renderWithProviders(<Harness initial={{ folderIds: ["1legacy"] }} onState={(drive) => (state = drive)} />);

    expect(screen.getByTestId("s-drive-scope-included")).toHaveTextContent("1legacy");
    await user.click(screen.getByRole("button", { name: "Remove 1legacy" }));

    expect(state.folderIds).toEqual([]);
  });

  it("will not browse an unsaved source or one with unsaved edits", () => {
    const { unmount } = renderWithProviders(<Harness initial={{}} saved={false} />);
    expect(screen.getByTestId("s-drive-scope-browse")).toBeDisabled();
    unmount();

    renderWithProviders(<Harness initial={{}} unsaved />);
    expect(screen.getByTestId("s-drive-scope-browse")).toBeDisabled();
    expect(listFolders).not.toHaveBeenCalled();
  });

  it("browses the drive lazily, one level at a time, and picks folders by id with their path", async () => {
    const user = userEvent.setup();
    listFolders.mockImplementation(async (_kb, _src, _version, parentId) =>
      parentId === "root"
        ? { folders: [{ id: "fSales", name: "Sales" }], nextPageToken: null }
        : { folders: [{ id: "fOffers", name: "Offers" }, { id: "fArchive", name: "Archive" }], nextPageToken: null },
    );
    let state: GoogleDriveSource = {};
    renderWithProviders(<Harness initial={{}} onState={(drive) => (state = drive)} />);

    await user.click(screen.getByTestId("s-drive-scope-browse"));
    expect(await screen.findByText("Sales")).toBeInTheDocument();
    expect(listFolders).toHaveBeenCalledTimes(1);
    expect(listFolders).toHaveBeenCalledWith("kb1", "src1", 3, "root", null);

    await user.click(screen.getByTestId("s-drive-picker-expand-fSales"));
    expect(await screen.findByText("Offers")).toBeInTheDocument();
    expect(listFolders).toHaveBeenLastCalledWith("kb1", "src1", 3, "fSales", null);

    await user.click(screen.getByTestId("s-drive-picker-include-fOffers"));
    await user.click(screen.getByTestId("s-drive-picker-exclude-fArchive"));

    expect(state.folders).toEqual([{ id: "fOffers", label: "Sales/Offers" }]);
    expect(state.excludeFolders).toEqual([{ id: "fArchive", label: "Sales/Archive" }]);
    expect(screen.getByTestId("s-drive-picker-state-fOffers")).toHaveTextContent("Read");
    expect(screen.getByTestId("s-drive-picker-include-fOffers")).toBeDisabled();
  });

  it("pages a large level with the token the server hands out", async () => {
    const user = userEvent.setup();
    listFolders
      .mockResolvedValueOnce({ folders: [{ id: "f1", name: "One" }], nextPageToken: "p1" })
      .mockResolvedValueOnce({ folders: [{ id: "f2", name: "Two" }], nextPageToken: null });
    renderWithProviders(<Harness initial={{}} />);

    await user.click(screen.getByTestId("s-drive-scope-browse"));
    await user.click(await screen.findByTestId("s-drive-picker-more"));

    expect(await screen.findByText("Two")).toBeInTheDocument();
    expect(listFolders).toHaveBeenLastCalledWith("kb1", "src1", 3, "root", "p1");
    expect(screen.queryByTestId("s-drive-picker-more")).not.toBeInTheDocument();
  });

  it("shows why a level could not be listed", async () => {
    const user = userEvent.setup();
    listFolders.mockRejectedValue(new Error("Shared Drive is not visible"));
    renderWithProviders(<Harness initial={{}} />);

    await user.click(screen.getByTestId("s-drive-scope-browse"));

    expect(await screen.findByTestId("s-drive-picker-error")).toHaveTextContent("Shared Drive is not visible");
  });
});
