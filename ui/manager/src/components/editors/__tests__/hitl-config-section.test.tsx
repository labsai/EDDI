import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";

import { HitlConfigSection } from "@/components/editors/agent-config-sections";
import type { Agent } from "@/lib/api/agents";

const mockOnChange = vi.fn();

/** Expand the collapsible section (collapsed by default when HITL is off). */
function expandSection() {
  fireEvent.click(screen.getByRole("button", { name: /Human-in-the-Loop/i }));
}

describe("HitlConfigSection", () => {
  beforeEach(() => mockOnChange.mockReset());

  it("enabling adds a default hitlConfig (wait-indefinitely)", () => {
    renderWithProviders(<HitlConfigSection agent={{}} onChange={mockOnChange} />);
    expandSection();

    expect(screen.queryByTestId("hitl-timeout-policy")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("hitl-config-enabled"));

    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          hitlConfig: expect.objectContaining({ timeoutPolicy: "WAIT_INDEFINITELY" }),
        }));
  });

  it("shows the approval-timeout input only for a finite policy", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT15M" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    // hitlConfig present → section is open by default.
    expect(screen.getByTestId("hitl-timeout-policy")).toBeInTheDocument();
    expect(screen.getByTestId("hitl-approval-timeout")).toBeInTheDocument();
  });

  it("hides the approval-timeout input for wait-indefinitely", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", approvalTimeout: null } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    expect(screen.queryByTestId("hitl-approval-timeout")).not.toBeInTheDocument();
  });

  it("seeds a valid default approvalTimeout when switching to a finite policy", () => {
    // Guards against the silent-400: a finite policy with approvalTimeout=null is
    // rejected by the backend, so the UI must seed a valid timeout in the same save.
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    fireEvent.change(screen.getByTestId("hitl-timeout-policy"), { target: { value: "AUTO_APPROVE" } });
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          hitlConfig: expect.objectContaining({ timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT15M" }),
        }));
  });

  it("changing the timeout policy patches hitlConfig", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    fireEvent.change(screen.getByTestId("hitl-timeout-policy"), { target: { value: "ABORT" } });
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          hitlConfig: expect.objectContaining({ timeoutPolicy: "ABORT" }),
        }));
  });

  it("keeps an invalid finite-policy timeout out of the draft, but records a valid one", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "AUTO_APPROVE", approvalTimeout: "PT15M" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    const input = screen.getByTestId("hitl-approval-timeout");

    // Invalid entry under a finite policy is not saved (would be a backend 400).
    fireEvent.change(input, { target: { value: "15m" } });
    fireEvent.blur(input);
    expect(mockOnChange).not.toHaveBeenCalled();

    // A valid entry commits.
    fireEvent.change(input, { target: { value: "PT30M" } });
    fireEvent.blur(input);
    expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
          hitlConfig: expect.objectContaining({ approvalTimeout: "PT30M" }),
        }));
  });

  it("disabling removes hitlConfig entirely", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    fireEvent.click(screen.getByTestId("hitl-config-enabled"));
    const next = mockOnChange.mock.calls[0]![0];
    expect(next.hitlConfig).toBeUndefined();
  });

  it("caps the approval-reason input at 500 characters", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
    expect(screen.getByTestId("hitl-pause-reason")).toHaveAttribute("maxLength", "500");
  });

  describe("tool-level approval gating", () => {
    it("enabling tool gating adds an empty toolApprovals block", () => {
      const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      expect(screen.queryByTestId("tool-approvals-editor")).not.toBeInTheDocument();
      fireEvent.click(screen.getByTestId("hitl-tool-enabled"));
      expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
            hitlConfig: expect.objectContaining({ toolApprovals: {} }),
          }));
    });

    it("renders the tool-approvals editor when a block is present", () => {
      const agent: Agent = {
        hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", toolApprovals: { requireApproval: ["mcp:*"] } },
      };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      expect(screen.getByTestId("tool-approvals-editor")).toBeInTheDocument();
      expect(screen.getByTestId("hitl-tool-require")).toHaveValue("mcp:*");
    });

    it("shows the AUTO_APPROVE demotion warning when the tool block inherits it", () => {
      const agent: Agent = {
        hitlConfig: {
          timeoutPolicy: "AUTO_APPROVE",
          approvalTimeout: "PT15M",
          toolApprovals: { requireApproval: ["mcp:*"] },
        },
      };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      expect(screen.getByTestId("hitl-tool-demotion-warning")).toBeInTheDocument();
    });

    it("hides the demotion warning once the tool block sets its own policy", () => {
      const agent: Agent = {
        hitlConfig: {
          timeoutPolicy: "AUTO_APPROVE",
          approvalTimeout: "PT15M",
          toolApprovals: { requireApproval: ["mcp:*"], timeoutPolicy: "AUTO_REJECT", approvalTimeout: "PT10M" },
        },
      };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      expect(screen.queryByTestId("hitl-tool-demotion-warning")).not.toBeInTheDocument();
    });

    it("disabling tool gating clears the block to null", () => {
      const agent: Agent = {
        hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", toolApprovals: { requireApproval: ["mcp:*"] } },
      };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      fireEvent.click(screen.getByTestId("hitl-tool-enabled"));
      expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
            hitlConfig: expect.objectContaining({ toolApprovals: null }),
          }));
    });
  });

  describe("onRejection (E2)", () => {
    it("defaults to SKIP and shows no revision bound", () => {
      const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);

      expect(screen.getByTestId("hitl-on-rejection")).toHaveValue("SKIP");
      expect(screen.queryByTestId("hitl-max-revisions")).not.toBeInTheDocument();
    });

    it("REVISE seeds the default bound in the same patch", () => {
      const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      fireEvent.change(screen.getByTestId("hitl-on-rejection"), { target: { value: "REVISE" } });

      expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
        hitlConfig: expect.objectContaining({ onRejection: "REVISE", maxRevisionsPerTurn: 3 }),
      }));
    });

    it("back to SKIP stores the default by leaving both fields empty", () => {
      const agent: Agent = { hitlConfig: { onRejection: "REVISE", maxRevisionsPerTurn: 2 } };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      fireEvent.change(screen.getByTestId("hitl-on-rejection"), { target: { value: "SKIP" } });

      expect(mockOnChange).toHaveBeenCalledWith(expect.objectContaining({
        hitlConfig: expect.objectContaining({ onRejection: null, maxRevisionsPerTurn: null }),
      }));
    });

    it("clamps the bound to what the backend accepts (1..10)", () => {
      const agent: Agent = { hitlConfig: { onRejection: "REVISE", maxRevisionsPerTurn: 2 } };
      renderWithProviders(<HitlConfigSection agent={agent} onChange={mockOnChange} />);
      fireEvent.change(screen.getByTestId("hitl-max-revisions"), { target: { value: "42" } });
      expect(mockOnChange).toHaveBeenLastCalledWith(expect.objectContaining({
        hitlConfig: expect.objectContaining({ maxRevisionsPerTurn: 10 }),
      }));
      fireEvent.change(screen.getByTestId("hitl-max-revisions"), { target: { value: "0" } });
      expect(mockOnChange).toHaveBeenLastCalledWith(expect.objectContaining({
        hitlConfig: expect.objectContaining({ maxRevisionsPerTurn: 1 }),
      }));
    });
  });
});
