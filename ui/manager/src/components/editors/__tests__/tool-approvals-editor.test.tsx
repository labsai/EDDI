import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ToolApprovalsEditor } from "@/components/editors/tool-approvals-editor";
import type { ToolApprovalsConfig } from "@/lib/api/hitl";

function renderEditor(value: ToolApprovalsConfig = {}, agentTimeoutPolicy?: string) {
  const onChange = vi.fn();
  renderWithProviders(
    <ToolApprovalsEditor value={value} onChange={onChange} agentTimeoutPolicy={agentTimeoutPolicy} />,
  );
  return { onChange };
}

describe("ToolApprovalsEditor", () => {
  it("commits the requireApproval list (one pattern per line) on blur", () => {
    const { onChange } = renderEditor();
    const require = screen.getByTestId("hitl-tool-require");
    fireEvent.change(require, { target: { value: "mcp:*\ndelete_*\n" } });
    fireEvent.blur(require);
    expect(onChange).toHaveBeenCalledWith({ requireApproval: ["mcp:*", "delete_*"] });
  });

  it("shows an inline error for an invalid glob pattern", () => {
    renderEditor();
    const require = screen.getByTestId("hitl-tool-require");
    fireEvent.change(require, { target: { value: "bad pattern" } });
    // Live validation on the draft (no blur needed) marks the field invalid.
    expect(require.className).toMatch(/border-destructive/);
    expect(screen.getByText(/illegal characters/)).toBeInTheDocument();
  });

  it("flags an out-of-range maxPausesPerTurn", () => {
    renderEditor({ maxPausesPerTurn: 99 });
    expect(screen.getByText(/between 1 and 10/)).toBeInTheDocument();
  });

  it("commits an onNoProgress change immediately", () => {
    const { onChange } = renderEditor();
    fireEvent.change(screen.getByTestId("hitl-tool-no-progress"), { target: { value: "ABORT" } });
    expect(onChange).toHaveBeenCalledWith({ onNoProgress: "ABORT" });
  });

  it("seeds a default approvalTimeout when a finite tool-pause policy is chosen", () => {
    const { onChange } = renderEditor();
    fireEvent.change(screen.getByTestId("hitl-tool-timeout-policy"), { target: { value: "AUTO_REJECT" } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ timeoutPolicy: "AUTO_REJECT", approvalTimeout: "PT15M" }),
    );
  });

  it("shows the approval-timeout field only for a finite tool-pause policy", () => {
    renderEditor({ timeoutPolicy: "AUTO_REJECT", approvalTimeout: "PT10M" });
    expect(screen.getByTestId("hitl-tool-approval-timeout")).toBeInTheDocument();
  });

  it("caps pauseReason at 500 characters", () => {
    renderEditor();
    expect(screen.getByTestId("hitl-tool-pause-reason")).toHaveAttribute("maxLength", "500");
  });

  it("renders the demotion warning only when agent AUTO_APPROVE is inherited", () => {
    renderEditor({ requireApproval: ["mcp:*"] }, "AUTO_APPROVE");
    expect(screen.getByTestId("hitl-tool-demotion-warning")).toBeInTheDocument();
  });

  it("does not render the demotion warning under a non-AUTO_APPROVE agent policy", () => {
    renderEditor({ requireApproval: ["mcp:*"] }, "WAIT_INDEFINITELY");
    expect(screen.queryByTestId("hitl-tool-demotion-warning")).not.toBeInTheDocument();
  });
});
