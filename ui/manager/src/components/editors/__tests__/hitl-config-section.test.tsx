import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@/hooks/use-agents", () => ({
  useUpdateAgent: () => ({ mutate: mockMutate, isPending: false }),
}));

import { HitlConfigSection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";

/** Expand the collapsible section (collapsed by default when HITL is off). */
function expandSection() {
  fireEvent.click(screen.getByRole("button", { name: /Human-in-the-Loop/i }));
}

describe("HitlConfigSection", () => {
  beforeEach(() => mockMutate.mockReset());

  it("enabling adds a default hitlConfig (wait-indefinitely)", () => {
    renderWithProviders(<HitlConfigSection agent={{}} agentId="a1" version={3} />);
    expandSection();

    expect(screen.queryByTestId("hitl-timeout-policy")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("hitl-config-enabled"));

    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: "a1",
        version: 3,
        agent: expect.objectContaining({
          hitlConfig: expect.objectContaining({ timeoutPolicy: "WAIT_INDEFINITELY" }),
        }),
      }),
    );
  });

  it("shows the approval-timeout input only for a finite policy", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT15M" } };
    renderWithProviders(<HitlConfigSection agent={agent} agentId="a1" version={1} />);
    // hitlConfig present → section is open by default.
    expect(screen.getByTestId("hitl-timeout-policy")).toBeInTheDocument();
    expect(screen.getByTestId("hitl-approval-timeout")).toBeInTheDocument();
  });

  it("hides the approval-timeout input for wait-indefinitely", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", approvalTimeout: null } };
    renderWithProviders(<HitlConfigSection agent={agent} agentId="a1" version={1} />);
    expect(screen.queryByTestId("hitl-approval-timeout")).not.toBeInTheDocument();
  });

  it("seeds a valid default approvalTimeout when switching to a finite policy", () => {
    // Guards against the silent-400: a finite policy with approvalTimeout=null is
    // rejected by the backend, so the UI must seed a valid timeout in the same save.
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} agentId="a1" version={1} />);
    fireEvent.change(screen.getByTestId("hitl-timeout-policy"), { target: { value: "AUTO_APPROVE" } });
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          hitlConfig: expect.objectContaining({ timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT15M" }),
        }),
      }),
    );
  });

  it("changing the timeout policy patches hitlConfig", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} agentId="a1" version={1} />);
    fireEvent.change(screen.getByTestId("hitl-timeout-policy"), { target: { value: "ABORT" } });
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          hitlConfig: expect.objectContaining({ timeoutPolicy: "ABORT" }),
        }),
      }),
    );
  });

  it("does not persist an invalid finite-policy timeout, but commits a valid one on blur", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT15M" } };
    renderWithProviders(<HitlConfigSection agent={agent} agentId="a1" version={1} />);
    const input = screen.getByTestId("hitl-approval-timeout");

    // Invalid entry under a finite policy is not saved (would be a backend 400).
    fireEvent.change(input, { target: { value: "15m" } });
    fireEvent.blur(input);
    expect(mockMutate).not.toHaveBeenCalled();

    // A valid entry commits.
    fireEvent.change(input, { target: { value: "PT30M" } });
    fireEvent.blur(input);
    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        agent: expect.objectContaining({
          hitlConfig: expect.objectContaining({ approvalTimeout: "PT30M" }),
        }),
      }),
    );
  });

  it("disabling removes hitlConfig entirely", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} agentId="a1" version={1} />);
    fireEvent.click(screen.getByTestId("hitl-config-enabled"));
    const payload = mockMutate.mock.calls[0]![0];
    expect(payload.agent.hitlConfig).toBeUndefined();
  });
});
