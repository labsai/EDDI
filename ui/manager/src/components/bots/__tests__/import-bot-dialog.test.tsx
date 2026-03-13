import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ImportBotDialog } from "@/components/bots/import-bot-dialog";

// vi.hoisted ensures these are available when vi.mock factory runs
const { mockImportMutate, mockPreviewMutate, mockMergeMutate } = vi.hoisted(
  () => ({
    mockImportMutate: vi.fn(),
    mockPreviewMutate: vi.fn(),
    mockMergeMutate: vi.fn(),
  })
);

vi.mock("@/hooks/use-backup", () => ({
  useImportBot: () => ({
    mutate: mockImportMutate,
    isPending: false,
  }),
  usePreviewImport: () => ({
    mutate: mockPreviewMutate,
    isPending: false,
  }),
  useImportBotMerge: () => ({
    mutate: mockMergeMutate,
    isPending: false,
  }),
}));

function renderDialog(
  props?: Partial<{
    open: boolean;
    onClose: () => void;
    onSuccess: () => void;
  }>
) {
  const defaultProps = {
    open: true,
    onClose: vi.fn(),
    onSuccess: vi.fn(),
    ...props,
  };
  return {
    ...renderWithProviders(<ImportBotDialog {...defaultProps} />),
    ...defaultProps,
  };
}

async function uploadFile(user: ReturnType<typeof userEvent.setup>) {
  const fileInput = screen.getByTestId("import-file-input");
  await user.upload(
    fileInput,
    new File(["zip"], "bot.zip", { type: "application/zip" })
  );
}

const PREVIEW_RESPONSE = {
  botOriginId: "origin-bot-1",
  botName: "Weather Bot",
  resources: [
    { originId: "o1", resourceType: "bot", name: "WBot Config", action: "UPDATE", localId: "b1", localVersion: 1 },
    { originId: "o2", resourceType: "package", name: "Main Pkg", action: "UPDATE", localId: "p1", localVersion: 1 },
    { originId: "o3", resourceType: "behavior", name: "Greeting Rules", action: "CREATE", localId: null, localVersion: null },
  ],
};

async function navigateToPreview(user: ReturnType<typeof userEvent.setup>) {
  await uploadFile(user);
  mockPreviewMutate.mockImplementation(
    (_file: File, opts: { onSuccess?: (data: unknown) => void }) => {
      opts.onSuccess?.(PREVIEW_RESPONSE);
    }
  );
  await user.click(screen.getByTestId("strategy-merge"));
  await user.click(screen.getByTestId("import-confirm-strategy"));
}

beforeEach(() => {
  mockImportMutate.mockReset();
  mockPreviewMutate.mockReset();
  mockMergeMutate.mockReset();
});

describe("ImportBotDialog", () => {
  // --- Rendering ---

  it("renders nothing when closed", () => {
    renderDialog({ open: false });
    expect(screen.queryByTestId("import-bot-dialog")).not.toBeInTheDocument();
  });

  it("renders upload drop zone when open", () => {
    renderDialog();
    expect(screen.getByTestId("import-bot-dialog")).toBeInTheDocument();
    expect(screen.getByTestId("import-drop-zone")).toBeInTheDocument();
  });

  // --- Upload step ---

  it("transitions to strategy step after file selection", async () => {
    renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);
    expect(screen.getByTestId("strategy-create")).toBeInTheDocument();
    expect(screen.getByTestId("strategy-merge")).toBeInTheDocument();
  });

  it("shows file name and size", async () => {
    renderDialog();
    const user = userEvent.setup();
    const fileInput = screen.getByTestId("import-file-input");
    await user.upload(
      fileInput,
      new File(["x".repeat(2048)], "my-bot-export.zip", { type: "application/zip" })
    );
    expect(screen.getByText("my-bot-export.zip")).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
  });

  // --- Strategy step ---

  it("create strategy is selected by default", async () => {
    renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);
    const radio = screen.getByTestId("strategy-create").querySelector("input[type='radio']") as HTMLInputElement;
    expect(radio.checked).toBe(true);
  });

  it("can switch to merge strategy", async () => {
    renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);
    await user.click(screen.getByTestId("strategy-merge"));
    const radio = screen.getByTestId("strategy-merge").querySelector("input[type='radio']") as HTMLInputElement;
    expect(radio.checked).toBe(true);
  });

  it("Import Now calls import mutation", async () => {
    renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);
    await user.click(screen.getByTestId("import-confirm-strategy"));

    expect(mockImportMutate).toHaveBeenCalledTimes(1);
    expect(mockImportMutate.mock.calls[0]![0].name).toBe("bot.zip");
  });

  it("create import calls onSuccess on completion", async () => {
    const { onSuccess } = renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);

    mockImportMutate.mockImplementation(
      (_file: File, opts: { onSuccess?: () => void }) => {
        opts.onSuccess?.();
      }
    );
    await user.click(screen.getByTestId("import-confirm-strategy"));
    expect(onSuccess).toHaveBeenCalled();
  });

  // --- Preview step ---

  it("Preview Changes calls preview mutation", async () => {
    renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);
    await user.click(screen.getByTestId("strategy-merge"));
    await user.click(screen.getByTestId("import-confirm-strategy"));

    expect(mockPreviewMutate).toHaveBeenCalledTimes(1);
    expect(mockPreviewMutate.mock.calls[0]![0]).toBeInstanceOf(File);
  });

  it("preview renders resource table", async () => {
    renderDialog();
    const user = userEvent.setup();
    await navigateToPreview(user);

    // Bot name header
    expect(screen.getByText("Weather Bot")).toBeInTheDocument();

    // Table has rows with resource names
    expect(screen.getByText("Main Pkg")).toBeInTheDocument();
    expect(screen.getByText("Greeting Rules")).toBeInTheDocument();

    // Action badges: 2 updates + 1 new
    expect(screen.getAllByText("Update").length).toBe(2);
    expect(screen.getAllByText("New").length).toBe(1);

    // Merge button visible
    expect(screen.getByTestId("import-confirm-merge")).toBeInTheDocument();
  });

  it("preview has checkboxes, all checked by default", async () => {
    renderDialog();
    const user = userEvent.setup();
    await navigateToPreview(user);

    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes.length).toBe(4); // 3 resources + 1 select-all
    checkboxes.forEach((cb) => expect(cb).toBeChecked());
  });

  it("unchecking a checkbox updates selection", async () => {
    renderDialog();
    const user = userEvent.setup();
    await navigateToPreview(user);

    const checkboxes = screen.getAllByRole("checkbox");
    await user.click(checkboxes[1]!); // first resource row
    expect(checkboxes[1]!).not.toBeChecked();
  });

  it("merge confirm calls merge mutation with selected IDs and triggers onSuccess", async () => {
    const { onSuccess } = renderDialog();
    const user = userEvent.setup();
    await navigateToPreview(user);

    mockMergeMutate.mockImplementation(
      (_args: unknown, opts: { onSuccess?: () => void }) => {
        opts.onSuccess?.();
      }
    );
    await user.click(screen.getByTestId("import-confirm-merge"));

    expect(mockMergeMutate).toHaveBeenCalledTimes(1);
    const args = mockMergeMutate.mock.calls[0]![0];
    expect(args.file).toBeInstanceOf(File);
    expect(args.selectedOriginIds).toEqual(expect.arrayContaining(["o1", "o2", "o3"]));
    expect(onSuccess).toHaveBeenCalled();
  });

  // --- Navigation ---

  it("back button returns to upload", async () => {
    renderDialog();
    const user = userEvent.setup();
    await uploadFile(user);
    await user.click(screen.getByText("Back"));
    expect(screen.getByTestId("import-drop-zone")).toBeInTheDocument();
  });

  it("X button calls onClose", async () => {
    const { onClose } = renderDialog();
    const user = userEvent.setup();
    const btn = screen.getByTestId("import-bot-dialog").querySelector("button");
    if (btn) {
      await user.click(btn);
      expect(onClose).toHaveBeenCalled();
    }
  });
});
