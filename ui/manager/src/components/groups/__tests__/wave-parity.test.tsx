import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { AgentResponseCard } from "@/components/groups/agent-response-card";
import { DecisionRecordCard } from "@/components/groups/decision-record-card";
import { DiscussionInput } from "@/components/groups/discussion-input";
import { PhaseHeader } from "@/components/groups/phase-header";
import { TaskBoard } from "@/components/groups/task-board";
import { MAX_GROUP_QUESTION_CHARS } from "@/lib/api/groups";
import type { DecisionRecord, TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";

const entry = (type: TranscriptEntryType, content = "…"): TranscriptEntry => ({
  speakerAgentId: "agent-001",
  speakerDisplayName: "Review Agent",
  type,
  content,
  timestamp: "2026-06-01T10:30:00Z",
  phaseIndex: 0,
  phaseName: "Synthesis",
  errorReason: null,
  targetAgentId: null,
});

/**
 * The regression: the card indexed ENTRY_TYPE_INFO and dereferenced `.label`,
 * so a transcript containing any of the eleven entry types added by EDDI's Wave
 * 0 threw a TypeError and blanked the view. FOLLOW_UP is the sharpest case —
 * the Manager produces it itself, via `followupGroupMember`.
 */
describe("AgentResponseCard — Wave 0 entry types", () => {
  const waveTypes: TranscriptEntryType[] = [
    "FOLLOW_UP", "ABSTAINED", "DISSENT", "CONVERGENCE", "FACILITATION",
    "VOTE", "PROPOSAL", "BARGAIN", "HUMAN_INPUT", "RETRO", "BID",
  ];

  it.each(waveTypes)("renders a %s entry", (type) => {
    renderWithProviders(<AgentResponseCard entry={entry(type, "content here")} />);
    expect(screen.getByText("content here")).toBeInTheDocument();
    expect(screen.getByText("Review Agent")).toBeInTheDocument();
  });

  it("renders an entry type this build has never heard of", () => {
    renderWithProviders(
      <AgentResponseCard entry={entry("SOME_FUTURE_TYPE" as TranscriptEntryType, "still visible")} />,
    );
    expect(screen.getByText("still visible")).toBeInTheDocument();
    expect(screen.getByText("Some Future Type")).toBeInTheDocument();
  });

  it("marks a dissent visually distinct from ordinary prose", () => {
    const { container } = renderWithProviders(<AgentResponseCard entry={entry("DISSENT", "I disagree")} />);
    expect(container.querySelector(".border-red-500\\/30")).not.toBeNull();
  });

  /**
   * The backend records an abstention with null content on purpose — "the point
   * of an abstention is that there is no position". Reporting a deliberate pass
   * as "No response" would read as a failed turn.
   */
  it("reads an empty abstention as a pass, not as a failure", () => {
    renderWithProviders(
      <AgentResponseCard entry={{ ...entry("ABSTAINED"), content: null }} />,
    );
    expect(screen.getByText(/Declined to add anything new/i)).toBeInTheDocument();
    expect(screen.queryByText(/No response/i)).not.toBeInTheDocument();
  });

  it("still says 'No response' for an ordinary empty turn", () => {
    renderWithProviders(<AgentResponseCard entry={{ ...entry("OPINION"), content: null }} />);
    expect(screen.getByText(/No response/i)).toBeInTheDocument();
  });
});

describe("DecisionRecordCard", () => {
  const base: DecisionRecord = {
    type: "VERDICT", outcome: "CON wins (PRO 4/10, CON 9/10)", winner: "CON",
    tally: { PRO: 4, CON: 9 }, dissents: [], method: "debate-judgment",
    decidedAtPhase: "Judgment",
  };

  it("shows the winner, outcome and tally", () => {
    renderWithProviders(<DecisionRecordCard decision={base} />);
    expect(screen.getByTestId("decision-winner")).toHaveTextContent("CON");
    expect(screen.getByTestId("decision-outcome")).toHaveTextContent("CON wins");
    const tally = screen.getByTestId("decision-tally");
    expect(within(tally).getByText("PRO")).toBeInTheDocument();
    expect(within(tally).getByText("9")).toBeInTheDocument();
  });

  it("says 'tie' rather than inventing a winner", () => {
    renderWithProviders(<DecisionRecordCard decision={{ ...base, winner: null }} />);
    expect(screen.getByTestId("decision-tie")).toBeInTheDocument();
    expect(screen.queryByTestId("decision-winner")).not.toBeInTheDocument();
  });

  it("renders the minority report", () => {
    renderWithProviders(
      <DecisionRecordCard
        decision={{
          ...base,
          dissents: [
            { agentId: "a1", displayName: "Backend Expert", position: "The migration cost is understated." },
          ],
        }}
      />,
    );
    expect(screen.getByText("Backend Expert")).toBeInTheDocument();
    expect(screen.getByText("The migration cost is understated.")).toBeInTheDocument();
  });

  it("keeps an unparsed judgment verbatim instead of hiding the failure", () => {
    renderWithProviders(
      <DecisionRecordCard
        decision={{ ...base, type: "NONE", winner: null, tally: null, raw: "I think CON, probably." }}
      />,
    );
    expect(screen.getByText("I think CON, probably.")).toBeInTheDocument();
  });

  it("renders a tally whose values are not numbers", () => {
    renderWithProviders(
      <DecisionRecordCard
        decision={{ ...base, tally: { note: "n/a", detail: { a: 1 }, missing: null } }}
      />,
    );
    const tally = screen.getByTestId("decision-tally");
    expect(within(tally).getByText("n/a")).toBeInTheDocument();
    expect(within(tally).getByText("—")).toBeInTheDocument();
  });
});

describe("PhaseHeader — convergence", () => {
  const children = <div>entry</div>;

  it("shows the agreement score of a check that has not converged", () => {
    renderWithProviders(
      <PhaseHeader
        name="Discussion" type="OPINION" entryCount={2}
        convergence={{
          phaseIndex: 1, phaseName: "Discussion", repeat: 1,
          agreementScore: 0.62, converged: false, repeatsSkipped: null, reason: "still moving",
        }}
      >
        {children}
      </PhaseHeader>,
    );
    const box = screen.getByTestId("phase-convergence-1");
    expect(box).toHaveTextContent("0.62");
    expect(box).toHaveTextContent("still moving");
  });

  it("reports the rounds a converged phase skipped", () => {
    renderWithProviders(
      <PhaseHeader
        name="Round 2" type="OPINION" entryCount={3}
        convergence={{
          phaseIndex: 2, phaseName: "Round 2", repeat: 1,
          agreementScore: 0.91, converged: true, repeatsSkipped: 3, reason: "agreed",
        }}
      >
        {children}
      </PhaseHeader>,
    );
    expect(screen.getByTestId("phase-convergence-2")).toHaveTextContent("3");
  });

  it("renders nothing extra when no check ran", () => {
    renderWithProviders(
      <PhaseHeader name="Opinions" type="OPINION" entryCount={1}>{children}</PhaseHeader>,
    );
    expect(screen.queryByTestId(/phase-convergence-/)).not.toBeInTheDocument();
  });
});

describe("TaskBoard — agent-filed attribution", () => {
  const board = (filedBy?: string | null) => (
    <TaskBoard
      taskPlan={[{ id: "t1", subject: "Fix the migration", assignedTo: "Writer", priority: 1, filedBy }]}
      tasksInProgress={new Set()}
      tasksCompleted={new Set()}
      taskVerifications={new Map()}
      isStreaming={false}
    />
  );

  // The board renders each task twice — a desktop grid and a mobile list, one
  // of which is CSS-hidden — so every task testid legitimately matches twice.
  it("names the member that filed a task", () => {
    renderWithProviders(board("Backend Expert"));
    const chips = screen.getAllByTestId("task-filed-by-t1");
    expect(chips).toHaveLength(2);
    expect(chips[0]).toHaveTextContent("Backend Expert");
  });

  it("says nothing for a task the PLAN phase or the config authored", () => {
    renderWithProviders(board(null));
    expect(screen.queryByTestId("task-filed-by-t1")).not.toBeInTheDocument();
  });
});

describe("DiscussionInput — group attachments", () => {
  it("attaches a picked file and submits it with the question", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    const file = new File(["hello"], "notes.txt", { type: "text/plain" });
    await user.upload(screen.getByTestId("discussion-file-input"), file);

    expect(await screen.findByText("notes.txt")).toBeInTheDocument();

    await user.type(screen.getByTestId("discussion-input"), "Review this");
    await user.click(screen.getByTestId("start-discussion-btn"));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    const [question, attachments] = onSubmit.mock.calls[0]!;
    expect(question).toBe("Review this");
    expect(attachments).toHaveLength(1);
    expect(attachments[0]).toMatchObject({ fileName: "notes.txt", mimeType: "text/plain" });
    // Bare base64 — a `data:` prefix would be stored as part of the payload.
    expect(attachments[0].data).toBe(btoa("hello"));
  });

  it("hides the attach control on a continuation, which the backend rejects", () => {
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} mode="continue" />);
    expect(screen.queryByTestId("discussion-attach-btn")).not.toBeInTheDocument();
  });

  it("still calls onSubmit with one argument when nothing is attached", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.type(screen.getByTestId("discussion-input"), "Plain question");
    await user.click(screen.getByTestId("start-discussion-btn"));

    expect(onSubmit).toHaveBeenCalledWith("Plain question");
  });

  it("shows each attachment's size, so the total cap is discoverable", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);

    await user.upload(
      screen.getByTestId("discussion-file-input"),
      new File(["x".repeat(2048)], "big.txt", { type: "text/plain" }),
    );

    expect(await screen.findByText("big.txt")).toBeInTheDocument();
    expect(screen.getByTestId("discussion-attachments")).toHaveTextContent("2 KB");
  });

  it("ignores a file already staged rather than charging it to the budget", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={vi.fn()} />);
    const input = screen.getByTestId("discussion-file-input");
    const file = new File(["same"], "dup.txt", { type: "text/plain" });

    await user.upload(input, file);
    expect(await screen.findByText("dup.txt")).toBeInTheDocument();

    await user.upload(input, file);
    // One chip, not two.
    expect(screen.getAllByText("dup.txt")).toHaveLength(1);
  });

  /**
   * The backend caps the question at 50,000 chars and fans it out to every member
   * in every phase. Without a client guard, the whole oversized body is uploaded
   * and answered with a 400 the user never sees.
   */
  it("blocks a question past the backend's character ceiling", async () => {
    const onSubmit = vi.fn();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    // `user.type` would take a very long time for 50k characters.
    fireEvent.change(screen.getByTestId("discussion-input"), {
      target: { value: "x".repeat(MAX_GROUP_QUESTION_CHARS + 1) },
    });

    expect(screen.getByTestId("start-discussion-btn")).toBeDisabled();
    fireEvent.submit(screen.getByTestId("discussion-input").closest("form")!);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("accepts a question exactly at the ceiling", () => {
    const onSubmit = vi.fn();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    fireEvent.change(screen.getByTestId("discussion-input"), {
      target: { value: "x".repeat(MAX_GROUP_QUESTION_CHARS) },
    });

    expect(screen.getByTestId("start-discussion-btn")).not.toBeDisabled();
  });

  it("drops an attachment when its chip is dismissed", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    await user.upload(
      screen.getByTestId("discussion-file-input"),
      new File(["x"], "drop-me.txt", { type: "text/plain" }),
    );
    await screen.findByText("drop-me.txt");
    await user.click(screen.getByRole("button", { name: /drop-me\.txt/i }));

    expect(screen.queryByText("drop-me.txt")).not.toBeInTheDocument();

    await user.type(screen.getByTestId("discussion-input"), "q");
    await user.click(screen.getByTestId("start-discussion-btn"));
    expect(onSubmit).toHaveBeenCalledWith("q");
  });
});
