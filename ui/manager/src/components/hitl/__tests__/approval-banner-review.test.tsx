import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import { resumeConversation, type RulePauseDetails } from "@/lib/api/hitl";
import {
  EDITABLE_CONVERSATION_ID,
  editableApprovalStatus,
  lastEditableResume,
} from "@/test/mocks/hitl-review-handlers";

function rulePause(overrides: Partial<RulePauseDetails> = {}): RulePauseDetails {
  return { ...(editableApprovalStatus().pauseDetails as RulePauseDetails), ...overrides };
}

function confirm() {
  const dialog = screen.getByRole("dialog");
  fireEvent.click(within(dialog).getAllByRole("button").find((b) => !/go back/i.test(b.textContent ?? ""))!);
}

describe("ApprovalBanner — edit on approve and reason codes", () => {
  beforeEach(() => {
    lastEditableResume.decision = null;
    lastEditableResume.client = null;
  });

  it("approves with the edited answer, starting from the draft", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" pauseDetails={rulePause()} onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("edit-answer-toggle"));
    const editor = screen.getByTestId("edited-answer") as HTMLTextAreaElement;
    expect(editor.value).toBe("The release is Friday.");
    fireEvent.change(editor, { target: { value: "The release is Monday." } });
    expect(screen.getByTestId("edited-answer-count").textContent).toBe("22 / 40");
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();

    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, undefined, {
      editedAnswer: "The release is Monday.",
    });
  });

  it("an unchanged edit is a plain approval", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" pauseDetails={rulePause()} onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("edit-answer-toggle"));
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();

    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined);
  });

  it("an unchanged draft shows the plain-approval button and confirmation, an edited one the edit wording", () => {
    renderWithProviders(<ApprovalBanner surface="regular" pauseDetails={rulePause()} onDecide={vi.fn()} />);

    fireEvent.click(screen.getByTestId("edit-answer-toggle"));
    expect(screen.getByTestId("approve-button").textContent).not.toMatch(/with edits/i);
    fireEvent.click(screen.getByTestId("approve-button"));
    expect(screen.getByRole("dialog").textContent).not.toMatch(/replaces the agent's answer/i);
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /go back/i }));

    fireEvent.change(screen.getByTestId("edited-answer"), { target: { value: "The release is Monday." } });
    expect(screen.getByTestId("approve-button").textContent).toMatch(/with edits/i);
    fireEvent.click(screen.getByTestId("approve-button"));
    expect(screen.getByRole("dialog").textContent).toMatch(/replaces the agent's answer/i);
  });

  it("explains a refusal for redacted content", () => {
    renderWithProviders(
      <ApprovalBanner
        surface="regular"
        pauseDetails={rulePause({ lastEditRefused: { reason: "REDACTED_CONTENT" } })}
        onDecide={vi.fn()}
      />,
    );
    expect(screen.getByTestId("last-edit-refused").textContent).toMatch(/redacted/i);
  });

  it("an unchanged over-cap or blank draft is still a plain approval", () => {
    for (const draft of ["x".repeat(60), ""]) {
      const onDecide = vi.fn();
      const base = rulePause();
      const pause = rulePause({ editableAnswer: { ...base.editableAnswer!, draft } });
      const { unmount } = renderWithProviders(<ApprovalBanner surface="regular" pauseDetails={pause} onDecide={onDecide} />);

      fireEvent.click(screen.getByTestId("edit-answer-toggle"));
      fireEvent.click(screen.getByTestId("approve-button"));
      confirm();

      expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined);
      unmount();
    }
  });

  it("refuses an edit over the cap or empty, before any request", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" pauseDetails={rulePause()} onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("edit-answer-toggle"));
    fireEvent.change(screen.getByTestId("edited-answer"), { target: { value: "x".repeat(41) } });
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();
    expect(onDecide).not.toHaveBeenCalled();
    expect(screen.getByTestId("approval-submit-error")).toBeTruthy();

    fireEvent.change(screen.getByTestId("edited-answer"), { target: { value: "   " } });
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();
    expect(onDecide).not.toHaveBeenCalled();
  });

  it("a JSON answer must stay a JSON object", () => {
    const onDecide = vi.fn();
    renderWithProviders(
      <ApprovalBanner
        surface="regular"
        pauseDetails={rulePause({ editableAnswer: { draft: '{"a":1}', format: "json", maxChars: 100 } })}
        onDecide={onDecide}
      />,
    );

    fireEvent.click(screen.getByTestId("edit-answer-toggle"));
    fireEvent.change(screen.getByTestId("edited-answer"), { target: { value: "[1,2]" } });
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();
    expect(onDecide).not.toHaveBeenCalled();

    fireEvent.change(screen.getByTestId("edited-answer"), { target: { value: '{"a":2}' } });
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();
    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, undefined, { editedAnswer: '{"a":2}' });
  });

  it("sends a reason code with a rejection and drops it on a plain approval", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" pauseDetails={rulePause()} onDecide={onDecide} />);

    fireEvent.change(screen.getByTestId("reason-code"), { target: { value: "TONE" } });
    fireEvent.click(screen.getByTestId("reject-button"));
    confirm();
    expect(onDecide).toHaveBeenLastCalledWith("REJECTED", undefined, undefined, undefined, { reasonCode: "TONE" });

    fireEvent.click(screen.getByTestId("approve-button"));
    confirm();
    expect(onDecide).toHaveBeenLastCalledWith("APPROVED", undefined, undefined);
  });

  it("shows the original and why the last edit was refused", () => {
    renderWithProviders(
      <ApprovalBanner
        surface="regular"
        pauseDetails={rulePause({ lastEditRefused: { reason: "SCREENED_OUT", guardrail: "pii" } })}
        onDecide={vi.fn()}
      />,
    );
    expect(screen.getByTestId("last-edit-refused").textContent).toContain("pii");
    fireEvent.click(screen.getByTestId("edit-answer-toggle"));
    fireEvent.click(screen.getByTestId("show-original"));
    expect(screen.getByTestId("original-answer").textContent).toBe("The release is Friday.");
  });

  it("a group banner never offers an answer edit", () => {
    renderWithProviders(<ApprovalBanner surface="group" pauseDetails={rulePause()} onDecide={vi.fn()} />);
    expect(screen.queryByTestId("edit-answer-toggle")).toBeNull();
  });

  it("the Manager names itself on a resume (X-EDDI-Client: manager)", async () => {
    await resumeConversation(EDITABLE_CONVERSATION_ID, { verdict: "APPROVED", editedAnswer: "Better" });
    expect(lastEditableResume.client).toBe("manager");
    expect(lastEditableResume.decision?.editedAnswer).toBe("Better");
  });
});
