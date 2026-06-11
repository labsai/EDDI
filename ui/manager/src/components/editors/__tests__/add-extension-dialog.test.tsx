import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AddExtensionDialog } from "@/components/editors/add-extension-dialog";
import type { ExtensionDescriptor } from "@/lib/api/extensions";

const mockExtensionTypes: ExtensionDescriptor[] = [
  {
    type: "eddi://ai.labs.parser",
    displayName: "Input Parser",
    configs: {},
    extensions: {},
  },
  {
    type: "eddi://ai.labs.llm",
    displayName: "LLM Configuration",
    configs: {},
    extensions: {},
  },
  {
    type: "eddi://ai.labs.rules",
    displayName: "Rules",
    configs: {},
    extensions: {},
  },
];

// Mock the extension types hook
vi.mock("@/hooks/use-extensions-store", () => ({
  useExtensionTypes: () => ({
    data: mockExtensionTypes,
    isLoading: false,
  }),
}));

// Mock resource APIs
vi.mock("@/lib/api/resources", async () => {
  const actual = await vi.importActual("@/lib/api/resources");
  return {
    ...actual,
    getResourceDescriptors: vi.fn().mockResolvedValue([]),
    createResource: vi.fn().mockResolvedValue({
      location: "http://localhost/rulestore/rulesets/new-123?version=1",
    }),
  };
});

vi.mock("@/lib/api/agents", () => ({
  parseResourceUri: vi.fn(() => ({
    id: "new-123",
    version: 1,
  })),
}));

describe("AddExtensionDialog", () => {
  const onClose = vi.fn();
  const onSelect = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns null when not open", () => {
    renderWithProviders(
      <AddExtensionDialog open={false} onClose={onClose} onSelect={onSelect} />
    );
    expect(
      screen.queryByTestId("add-extension-dialog")
    ).not.toBeInTheDocument();
  });

  it("renders dialog when open", () => {
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    expect(
      screen.getByTestId("add-extension-dialog")
    ).toBeInTheDocument();
  });

  it("shows Add Task title", () => {
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    expect(screen.getByText("Add Task")).toBeInTheDocument();
  });

  it("shows search input", () => {
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    expect(screen.getByTestId("extension-search")).toBeInTheDocument();
  });

  it("displays extension types", () => {
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    expect(
      screen.getByTestId("ext-option-eddi://ai.labs.parser")
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("ext-option-eddi://ai.labs.llm")
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("ext-option-eddi://ai.labs.rules")
    ).toBeInTheDocument();
  });

  it("shows footer hint text", () => {
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    expect(
      screen.getByText("Select a task type to add to the pipeline")
    ).toBeInTheDocument();
  });

  it("calls onClose when close button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    const closeBtn = screen.getByLabelText("Close");
    await user.click(closeBtn);
    expect(onClose).toHaveBeenCalled();
  });

  it("selects parser (no resource store) directly", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    await user.click(
      screen.getByTestId("ext-option-eddi://ai.labs.parser")
    );
    // Parser has no resource store, so it should call onSelect directly
    expect(onSelect).toHaveBeenCalledWith({
      descriptor: expect.objectContaining({ type: "eddi://ai.labs.parser" }),
    });
    expect(onClose).toHaveBeenCalled();
  });

  it("shows step 2 when selecting LLM (has resource store)", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    await user.click(
      screen.getByTestId("ext-option-eddi://ai.labs.llm")
    );
    // Should go to step 2 — "Choose Config" title
    await waitFor(() => {
      expect(screen.getByText("Choose Config")).toBeInTheDocument();
    });
    expect(screen.getByTestId("create-new-config")).toBeInTheDocument();
    expect(screen.getByTestId("back-to-types")).toBeInTheDocument();
  });

  it("goes back to step 1 when back button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    await user.click(
      screen.getByTestId("ext-option-eddi://ai.labs.llm")
    );
    await waitFor(() => {
      expect(screen.getByText("Choose Config")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("back-to-types"));
    await waitFor(() => {
      expect(screen.getByText("Add Task")).toBeInTheDocument();
    });
  });

  it("closes on Escape key", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("filters extension types by search", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AddExtensionDialog open={true} onClose={onClose} onSelect={onSelect} />
    );
    await user.click(screen.getByTestId("extension-search"));
    await user.type(screen.getByTestId("extension-search"), "llm");
    // LLM should still be visible, parser and rules should not
    expect(
      screen.getByTestId("ext-option-eddi://ai.labs.llm")
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("ext-option-eddi://ai.labs.parser")
    ).not.toBeInTheDocument();
  });
});
