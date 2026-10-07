import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AddExtensionDialog } from "@/components/editors/add-extension-dialog";
import type { ExtensionDescriptor } from "@/lib/api/extensions";

const mockExtensionTypes: ExtensionDescriptor[] = [
  { type: "eddi://ai.labs.llm", displayName: "LLM Configuration", configs: {}, extensions: {} },
];

vi.mock("@/hooks/use-extensions-store", () => ({
  useExtensionTypes: () => ({ data: mockExtensionTypes, isLoading: false }),
}));

const createResource = vi.fn();
const updateDescriptor = vi.fn();
vi.mock("@/lib/api/resources", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api/resources")>("@/lib/api/resources");
  return {
    ...actual,
    getResourceDescriptors: vi.fn().mockResolvedValue([]),
    createResource: (...args: unknown[]) => createResource(...args),
  };
});
vi.mock("@/lib/api/descriptors", () => ({
  updateDescriptor: (...args: unknown[]) => updateDescriptor(...args),
}));

describe("AddExtensionDialog — Create new", () => {
  const onSelect = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    createResource.mockResolvedValue({ location: "/llmstore/llms/new-123?version=1" });
    updateDescriptor.mockResolvedValue(undefined);
  });

  async function openCreateStep() {
    const user = userEvent.setup();
    renderWithProviders(<AddExtensionDialog open onClose={vi.fn()} onSelect={onSelect} />);
    await user.click(screen.getByTestId("ext-option-eddi://ai.labs.llm"));
    await screen.findByTestId("create-new-config");
    return user;
  }

  it("will not create an unnamed resource", async () => {
    const user = await openCreateStep();
    expect(screen.getByTestId("create-new-config")).toBeDisabled();
    await user.click(screen.getByTestId("create-new-config"));
    expect(createResource).not.toHaveBeenCalled();
  });

  it("creates the resource, stores the name and reports what it created", async () => {
    const user = await openCreateStep();
    await user.type(screen.getByTestId("new-config-name"), "  Support greeting ");
    await user.click(screen.getByTestId("create-new-config"));

    await waitFor(() => expect(onSelect).toHaveBeenCalledTimes(1));
    expect(updateDescriptor).toHaveBeenCalledWith("new-123", 1, { name: "Support greeting" });
    const result = onSelect.mock.calls[0]![0];
    expect(result.configUri).toContain("/llmstore/llms/new-123?version=1");
    // Reported as created, so the workflow page can offer to delete it again.
    expect(result.created).toMatchObject({ id: "new-123", version: 1, name: "Support greeting" });
  });

  it("still adds the step when only naming it failed", async () => {
    updateDescriptor.mockRejectedValue(new Error("nope"));
    const user = await openCreateStep();
    await user.type(screen.getByTestId("new-config-name"), "Greeting");
    await user.click(screen.getByTestId("create-new-config"));
    await waitFor(() => expect(onSelect).toHaveBeenCalledTimes(1));
    expect(onSelect.mock.calls[0]![0].created).toBeDefined();
  });

  it("does not report a picked existing resource as created", async () => {
    const { getResourceDescriptors } = await import("@/lib/api/resources");
    vi.mocked(getResourceDescriptors).mockResolvedValue([
      {
        resource: "eddi://ai.labs.llm/llmstore/llms/old-1?version=3",
        name: "Old LLM",
        description: "",
        createdOn: 0,
        lastModifiedOn: 0,
      },
    ]);
    const user = await openCreateStep();
    await user.click(await screen.findByTestId("existing-resource-old-1"));
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0]![0].created).toBeUndefined();
  });
});
