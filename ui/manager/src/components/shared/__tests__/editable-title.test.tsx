import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { EditableTitle } from "@/components/shared/editable-title";

const toastError = vi.fn();
vi.mock("sonner", () => ({ toast: { error: (...a: unknown[]) => toastError(...a) } }));

describe("EditableTitle", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows the name, or the fallback when there is none", () => {
    const { rerender } = renderWithProviders(
      <EditableTitle name="Support" fallback="Agent Detail" canEdit onSave={vi.fn()} />,
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Support");
    rerender(<EditableTitle name="" fallback="Agent Detail" canEdit onSave={vi.fn()} />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Agent Detail");
  });

  it("offers no rename to someone who cannot edit", () => {
    renderWithProviders(<EditableTitle name="Support" fallback="x" canEdit={false} onSave={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Rename" })).not.toBeInTheDocument();
  });

  it("saves a trimmed name and description, then closes", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderWithProviders(
      <EditableTitle name="Support" description="old" fallback="x" canEdit onSave={onSave} />,
    );
    await user.click(screen.getByRole("button", { name: "Rename" }));
    const name = screen.getByLabelText("Name");
    await user.clear(name);
    await user.type(name, "  Billing ");
    await user.clear(screen.getByLabelText("Description"));
    await user.type(screen.getByLabelText("Description"), "new text");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ name: "Billing", description: "new text" }));
    await waitFor(() => expect(screen.queryByLabelText("Name")).not.toBeInTheDocument());
  });

  it("refuses an empty name", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<EditableTitle name="Support" fallback="x" canEdit onSave={onSave} />);
    await user.click(screen.getByRole("button", { name: "Rename" }));
    await user.clear(screen.getByLabelText("Name"));
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(onSave).not.toHaveBeenCalled();
  });

  it("stays open and says why when saving fails", async () => {
    const onSave = vi.fn().mockRejectedValue(new Error("nope"));
    const user = userEvent.setup();
    renderWithProviders(<EditableTitle name="Support" fallback="x" canEdit onSave={onSave} />);
    await user.click(screen.getByRole("button", { name: "Rename" }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(toastError).toHaveBeenCalledWith("nope"));
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
  });

  it("cancels with Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<EditableTitle name="Support" fallback="x" canEdit onSave={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Rename" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();
  });
});
