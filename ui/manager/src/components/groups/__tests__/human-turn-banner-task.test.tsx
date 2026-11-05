import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { HumanTurnBanner } from "@/components/groups/human-turn-banner";
import { pendingHumanTask, type GroupConversation } from "@/lib/api/groups";

describe("HumanTurnBanner — task variant (humans as task-force executors)", () => {
  it("names the task and submits the result as plain content", () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <HumanTurnBanner
        displayName="Hannah"
        renderedPrompt="YOUR TASK: Sign-off"
        task={{ subject: "Sign-off", description: "legal review" }}
        onSubmit={onSubmit}
      />,
    );

    expect(screen.getByText(/A task is assigned to you, Hannah/)).toBeInTheDocument();
    expect(screen.getByTestId("human-turn-task")).toHaveTextContent("Sign-off — legal review");
    expect(screen.queryByTestId("human-turn-options")).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("human-turn-input"), { target: { value: " Signed " } });
    fireEvent.click(screen.getByTestId("human-turn-submit"));

    expect(onSubmit).toHaveBeenCalledWith("Signed");
  });

  it("pendingHumanTask finds the task a TASK pause is about", () => {
    const conversation = {
      pendingHumanInput: { detail: { kind: "TASK", taskId: "t2" } },
      taskList: { tasks: [{ id: "t1", subject: "Draft" }, { id: "t2", subject: "Sign-off" }] },
    } as unknown as GroupConversation;

    expect(pendingHumanTask(conversation)?.subject).toBe("Sign-off");
    expect(
      pendingHumanTask({ ...conversation, pendingHumanInput: { detail: { kind: "TIE_BREAK" } } } as unknown as GroupConversation),
    ).toBeUndefined();
    expect(pendingHumanTask(null)).toBeUndefined();
  });
});
