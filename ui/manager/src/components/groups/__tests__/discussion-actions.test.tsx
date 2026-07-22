import { describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { DiscussionActions } from "@/components/groups/discussion-actions";
import type { GroupConversationAction } from "@/lib/api/groups";

const MEMBERS = [
  { agentId: "agent1", displayName: "Support Agent", memberType: "AGENT" as const },
  { agentId: "agent2", displayName: "FAQ Agent", memberType: "AGENT" as const },
];

function renderActions(
  availableActions: GroupConversationAction[],
  overrides: Partial<{
    onContinue: (q: string) => void;
    onFollowup: (t: string, q: string) => void;
    onCloseDiscussion: () => void;
    isPending: boolean;
  }> = {},
) {
  const onContinue = overrides.onContinue ?? vi.fn();
  const onFollowup = overrides.onFollowup ?? vi.fn();
  const onCloseDiscussion = overrides.onCloseDiscussion ?? vi.fn();
  renderWithProviders(
    <DiscussionActions
      availableActions={availableActions}
      members={MEMBERS}
      isPending={overrides.isPending}
      onContinue={onContinue}
      onFollowup={onFollowup}
      onCloseDiscussion={onCloseDiscussion}
    />,
  );
  return { onContinue, onFollowup, onCloseDiscussion };
}

describe("DiscussionActions", () => {
  it("renders exactly the backend's availableActions (COMPLETED → all three)", () => {
    renderActions(["followup", "continue", "close"]);
    expect(screen.getByTestId("action-continue")).toBeInTheDocument();
    expect(screen.getByTestId("action-followup")).toBeInTheDocument();
    expect(screen.getByTestId("action-close")).toBeInTheDocument();
  });

  it("renders only Close for a terminal (FAILED/CANCELLED) conversation", () => {
    renderActions(["close"]);
    expect(screen.getByTestId("action-close")).toBeInTheDocument();
    expect(screen.queryByTestId("action-continue")).not.toBeInTheDocument();
    expect(screen.queryByTestId("action-followup")).not.toBeInTheDocument();
  });

  it("renders nothing when availableActions is empty (CLOSED — terminal)", () => {
    renderActions([]);
    expect(screen.queryByTestId("discussion-actions")).not.toBeInTheDocument();
    expect(screen.queryByTestId("action-close")).not.toBeInTheDocument();
  });

  it("continue is a separate composer that submits the typed question", async () => {
    const user = userEvent.setup();
    const onContinue = vi.fn();
    renderActions(["followup", "continue", "close"], { onContinue });

    await user.click(screen.getByTestId("action-continue"));
    const input = await screen.findByTestId("group-continue-input");
    await user.type(input, "Re-evaluate with the new data");
    await user.click(screen.getByTestId("group-continue-submit"));

    expect(onContinue).toHaveBeenCalledWith("Re-evaluate with the new data");
  });

  it("follow-up submits the selected member and question", async () => {
    const user = userEvent.setup();
    const onFollowup = vi.fn();
    renderActions(["followup", "continue", "close"], { onFollowup });

    await user.click(screen.getByTestId("action-followup"));
    const select = await screen.findByTestId("group-followup-member");
    await user.selectOptions(select, "agent2");
    await user.type(
      screen.getByTestId("group-followup-input"),
      "Can you expand on the GDPR risk?",
    );
    await user.click(screen.getByTestId("group-followup-submit"));

    expect(onFollowup).toHaveBeenCalledWith(
      "agent2",
      "Can you expand on the GDPR risk?",
    );
  });

  it("close is confirmed before firing — a single click does not close", async () => {
    const user = userEvent.setup();
    const onCloseDiscussion = vi.fn();
    renderActions(["followup", "continue", "close"], { onCloseDiscussion });

    await user.click(screen.getByTestId("action-close"));
    // The click opens a confirmation dialog — it must NOT fire the close yet.
    expect(onCloseDiscussion).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByText(/permanently ends all member conversations/i),
    ).toBeInTheDocument();

    await user.click(
      within(dialog).getByRole("button", { name: "Close discussion" }),
    );
    expect(onCloseDiscussion).toHaveBeenCalledTimes(1);
  });

  it("dismissing the close confirmation leaves the discussion open", async () => {
    const user = userEvent.setup();
    const onCloseDiscussion = vi.fn();
    renderActions(["close"], { onCloseDiscussion });

    await user.click(screen.getByTestId("action-close"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Go back" }));

    expect(onCloseDiscussion).not.toHaveBeenCalled();
  });
});
