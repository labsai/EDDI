import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@/hooks/use-groups", () => ({
  useUpdateGroup: () => ({ mutate: mockMutate, isPending: false }),
}));

import { SlackNotifyField } from "@/components/hitl/slack-notify-field";
import { HitlConfigSection } from "@/components/editors/agent-config-sections";
import { GroupHitlEditor } from "@/components/groups/group-hitl-editor";
import type { Agent } from "@/lib/api/agents";
import type { AgentGroupConfiguration } from "@/lib/api/groups";

describe("SlackNotifyField", () => {
  it("off without a value; switching on and typing a name hands over the trimmed name", () => {
    const onChange = vi.fn();
    renderWithProviders(<SlackNotifyField value={null} onChange={onChange} subject="agent" />);
    expect(screen.getByTestId("hitl-notify-slack-enabled")).not.toBeChecked();
    expect(screen.queryByTestId("hitl-notify-slack-integration")).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "  ops-slack " } });

    expect(onChange).toHaveBeenLastCalledWith("ops-slack");
  });

  it("an empty name or one with '|' is never handed over, and is reported as invalid", () => {
    const onChange = vi.fn();
    const onInvalidChange = vi.fn();
    renderWithProviders(<SlackNotifyField value={null} onChange={onChange} onInvalidChange={onInvalidChange} subject="agent" />);
    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));
    expect(onInvalidChange).toHaveBeenLastCalledWith(true);

    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "ops|slack" } });
    expect(onChange).not.toHaveBeenCalled();
    expect(onInvalidChange).toHaveBeenLastCalledWith(true);
    expect(screen.getByTestId("hitl-notify-slack-hint")).toHaveClass("text-destructive");

    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "ops-slack" } });
    expect(onInvalidChange).toHaveBeenLastCalledWith(false);
  });

  it("switching off removes a saved name and stops counting as invalid", () => {
    const onChange = vi.fn();
    const onInvalidChange = vi.fn();
    renderWithProviders(
      <SlackNotifyField value="ops-slack" onChange={onChange} onInvalidChange={onInvalidChange} subject="group" />,
    );
    expect(screen.getByTestId("hitl-notify-slack-integration")).toHaveValue("ops-slack");

    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "" } });
    expect(onInvalidChange).toHaveBeenLastCalledWith(true);
    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));

    expect(onChange).toHaveBeenLastCalledWith(null);
    expect(onInvalidChange).toHaveBeenLastCalledWith(false);
  });
});

describe("SlackNotifyField — owner changes", () => {
  it("shows the field off and empty when the parent clears the saved name", () => {
    const { rerender } = renderWithProviders(<SlackNotifyField value="ops-slack" onChange={vi.fn()} subject="agent" />);
    expect(screen.getByTestId("hitl-notify-slack-enabled")).toBeChecked();

    rerender(<SlackNotifyField value={null} onChange={vi.fn()} subject="agent" />);

    expect(screen.getByTestId("hitl-notify-slack-enabled")).not.toBeChecked();
    expect(screen.queryByTestId("hitl-notify-slack-integration")).not.toBeInTheDocument();
  });

  it("says that switching it off falls back to the deployment default", () => {
    renderWithProviders(<SlackNotifyField value="ops-slack" onChange={vi.fn()} subject="agent" />);
    expect(screen.getByTestId("hitl-notify-slack-hint").textContent).toContain("eddi.slack.hitl.notify.default-integration");
  });
});

describe("HitlConfigSection — notify.slack beside notify.email", () => {
  it("collapsing the section does not clear an unsavable Slack name", () => {
    const onValidityChange = vi.fn();
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", notify: { slack: { integration: "ops-slack" } } } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={vi.fn()} onValidityChange={onValidityChange} />);
    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "ops|slack" } });
    expect(onValidityChange).toHaveBeenLastCalledWith(true);

    fireEvent.click(screen.getByRole("button", { name: /Human-in-the-Loop/ }));

    expect(screen.queryByTestId("hitl-notify-slack-integration")).not.toBeInTheDocument();
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
  });

  it("adding the Slack card keeps the email recipients", () => {
    const onChange = vi.fn();
    const agent: Agent = {
      hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", notify: { email: { recipients: ["ops@example.com"] } } },
    };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={onChange} />);
    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));
    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "ops-slack" } });

    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      hitlConfig: expect.objectContaining({
        notify: { email: { recipients: ["ops@example.com"] }, slack: { integration: "ops-slack" } },
      }),
    }));
  });

  it("switching email off keeps the Slack card", () => {
    const onChange = vi.fn();
    const agent: Agent = {
      hitlConfig: {
        timeoutPolicy: "WAIT_INDEFINITELY",
        notify: { email: { recipients: ["ops@example.com"] }, slack: { integration: "ops-slack" } },
      },
    };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={onChange} />);
    fireEvent.click(screen.getByTestId("hitl-notify-email-enabled"));

    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      hitlConfig: expect.objectContaining({ notify: { slack: { integration: "ops-slack" } } }),
    }));
  });

  it("an invalid Slack name withholds the page's save", () => {
    const onValidityChange = vi.fn();
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={vi.fn()} onValidityChange={onValidityChange} />);
    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));

    expect(onValidityChange).toHaveBeenLastCalledWith(true);
  });
});

describe("GroupHitlEditor — notify.slack", () => {
  beforeEach(() => mockMutate.mockReset());

  const config = {
    name: "G",
    style: "ROUND_TABLE",
    maxRounds: 1,
    members: [],
    phases: [{ name: "Synthesis", type: "SYNTHESIS", participants: "ALL", turnOrder: "SEQUENTIAL", contextScope: "NONE",
      targetEachPeer: false, inputTemplate: null, repeats: 1, requiresApproval: true }],
    hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", granularity: "PHASE", onTaskRejection: "FAIL", approvalTimeout: null },
  } as unknown as AgentGroupConfiguration;

  it("saves the named integration as hitlConfig.notify.slack", () => {
    renderWithProviders(<GroupHitlEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />);
    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));
    fireEvent.change(screen.getByTestId("hitl-notify-slack-integration"), { target: { value: "ops-slack" } });
    fireEvent.click(screen.getByTestId("group-hitl-save"));

    expect(mockMutate.mock.calls[0]![0].config.hitlConfig.notify).toEqual({ slack: { integration: "ops-slack" } });
  });

  it("an empty name blocks the save", () => {
    renderWithProviders(<GroupHitlEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />);
    fireEvent.click(screen.getByTestId("hitl-notify-slack-enabled"));

    expect(screen.getByTestId("group-hitl-save")).toBeDisabled();
    fireEvent.click(screen.getByTestId("group-hitl-save"));
    expect(mockMutate).not.toHaveBeenCalled();
  });

  it("without a Slack card the saved notify is null", () => {
    renderWithProviders(<GroupHitlEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />);
    fireEvent.click(screen.getByTestId("group-hitl-save"));

    expect(mockMutate.mock.calls[0]![0].config.hitlConfig.notify).toBeNull();
  });
});
