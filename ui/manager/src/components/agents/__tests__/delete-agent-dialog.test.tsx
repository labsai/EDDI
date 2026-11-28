import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { DeleteAgentDialog } from "@/components/agents/delete-agent-dialog";

const conflict = { message: "Version is not current", lists: [{ field: "usedBy", items: ["wf-1"] }] };

function setup(over: Partial<React.ComponentProps<typeof DeleteAgentDialog>> = {}) {
  const props = {
    open: true,
    onOpenChange: vi.fn(),
    agentName: "Support",
    conflict: null,
    isPending: false,
    onConfirm: vi.fn(),
    onReload: vi.fn(),
    ...over,
  };
  renderWithProviders(<DeleteAgentDialog {...props} />);
  return props;
}

describe("DeleteAgentDialog", () => {
  it("names the agent and offers no reload before a conflict", () => {
    setup();
    expect(screen.getByText(/Support/)).toBeInTheDocument();
    expect(screen.queryByTestId("delete-agent-conflict")).not.toBeInTheDocument();
  });

  it("shows the server's message and lists after a 409, with Reload and no retry switch", async () => {
    const props = setup({ conflict });
    expect(screen.getByTestId("delete-agent-conflict-details-message")).toHaveTextContent("Version is not current");
    expect(screen.getByText("wf-1")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByTestId("delete-agent-reload"));
    expect(props.onReload).toHaveBeenCalledTimes(1);
    expect(props.onConfirm).not.toHaveBeenCalled();
  });

  it("keeps Delete enabled for the first confirmation", () => {
    setup();
    expect(screen.getByTestId("alert-dialog-confirm")).toBeEnabled();
  });

  it("disables Delete after a 409, so the refused request cannot be sent again", async () => {
    const props = setup({ conflict });
    expect(screen.getByTestId("alert-dialog-confirm")).toBeDisabled();
    await userEvent.setup().click(screen.getByTestId("alert-dialog-confirm"));
    expect(props.onConfirm).not.toHaveBeenCalled();
  });
});
