import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { DiscussionInput } from "@/components/groups/discussion-input";
import { DiscussionActions } from "@/components/groups/discussion-actions";
import { DiscussionTranscript } from "@/components/groups/discussion-transcript";
import { BoardInput } from "@/components/workforce/board-input";
import type { GroupConversation, TranscriptEntry } from "@/lib/api/groups";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";

/** A promise the test settles by hand — stands in for "the server answered". */
function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe("composers keep the draft until the request is accepted", () => {
  it("DiscussionInput keeps the question when the request is refused, and clears it when accepted", async () => {
    const gate = deferred<boolean>();
    const onSubmit = vi.fn(() => gate.promise);
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);

    const input = screen.getByTestId("discussion-input");
    await user.type(input, "Why?");
    await user.click(screen.getByTestId("start-discussion-btn"));

    // In flight: the text is still there, and the composer is locked.
    expect(input).toHaveValue("Why?");
    expect(input).toBeDisabled();

    await act(async () => gate.resolve(false));
    await waitFor(() => expect(input).not.toBeDisabled());
    expect(input).toHaveValue("Why?");

    const accepted = deferred<boolean>();
    onSubmit.mockReturnValueOnce(accepted.promise);
    await user.click(screen.getByTestId("start-discussion-btn"));
    await act(async () => accepted.resolve(true));
    await waitFor(() => expect(input).toHaveValue(""));
  });

  it("DiscussionInput keeps the question when the submit promise rejects", async () => {
    const onSubmit = vi.fn(() => Promise.reject(new Error("boom")));
    const user = userEvent.setup();
    renderWithProviders(<DiscussionInput onSubmit={onSubmit} />);
    const input = screen.getByTestId("discussion-input");
    await user.type(input, "Keep me");
    await user.click(screen.getByTestId("start-discussion-btn"));
    await waitFor(() => expect(input).not.toBeDisabled());
    expect(input).toHaveValue("Keep me");
  });

  it("BoardInput keeps the message when the send is refused", async () => {
    const gate = deferred<boolean>();
    const onSend = vi.fn(() => gate.promise);
    const user = userEvent.setup();
    renderWithProviders(<BoardInput onSend={onSend} />);

    const box = screen.getByRole("textbox");
    await user.type(box, "Plan the launch");
    await user.click(screen.getByTestId("board-send"));
    expect(box).toHaveValue("Plan the launch");

    await act(async () => gate.resolve(false));
    await waitFor(() => expect(box).not.toBeDisabled());
    expect(box).toHaveValue("Plan the launch");
  });

  it("the follow-up composer keeps its question when the follow-up fails", async () => {
    const gate = deferred<boolean>();
    const onFollowup = vi.fn(() => gate.promise);
    const user = userEvent.setup();
    renderWithProviders(
      <DiscussionActions
        availableActions={["followup", "close"]}
        members={[{ agentId: "a1", displayName: "Ana", memberType: "AGENT" }]}
        onFollowup={onFollowup}
        onCloseDiscussion={vi.fn()}
      />,
    );
    await user.click(screen.getByTestId("action-followup"));
    await user.type(screen.getByTestId("group-followup-input"), "And then?");
    await user.click(screen.getByTestId("group-followup-submit"));

    await act(async () => gate.resolve(false));
    await waitFor(() => expect(screen.getByTestId("group-followup-input")).not.toBeDisabled());
    expect(screen.getByTestId("group-followup-input")).toHaveValue("And then?");
  });

  it("offers only AGENT members for a follow-up — a HUMAN member has no agent to ask", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <DiscussionActions
        availableActions={["followup"]}
        members={[
          { agentId: "a1", displayName: "Ana the agent", memberType: "AGENT" },
          { agentId: "h1", displayName: "Hugo the human", memberType: "HUMAN" },
          { agentId: "g1", displayName: "Nested group", memberType: "GROUP" },
          { agentId: "a2", displayName: "Untyped agent" },
        ]}
        onFollowup={vi.fn()}
        onCloseDiscussion={vi.fn()}
      />,
    );
    await user.click(screen.getByTestId("action-followup"));
    const options = Array.from(
      screen.getByTestId("group-followup-member").querySelectorAll("option"),
    ).map((o) => o.textContent);
    expect(options).toEqual(["Ana the agent", "Untyped agent"]);
  });
});

function entry(i: number): TranscriptEntry {
  return {
    speakerAgentId: `agent-${i}`,
    speakerDisplayName: `Speaker ${i}`,
    content: `Statement number ${i}`,
    phaseIndex: 0,
    phaseName: "Opinion",
    type: "OPINION",
    timestamp: "2026-06-09T12:00:00Z",
    errorReason: null,
    targetAgentId: null,
  };
}

function streamOf(count: number): GroupStreamState {
  return {
    conversationId: "conv-live",
    isStreaming: true,
    state: "IN_PROGRESS",
    startedAt: "2026-06-09T12:00:00.000Z",
    transcript: Array.from({ length: count }, (_, i) => entry(i)),
    currentPhase: { index: 0, name: "Opinion", type: "OPINION" },
    activeSpeakers: new Set(),
    synthesizedAnswer: null,
    decision: null,
    convergence: new Map(),
    error: null,
    errorKind: null,
    taskPlan: null,
    taskVerifications: new Map(),
    tasksInProgress: new Set(),
    tasksCompleted: new Set(),
    hitlPause: null,
    hitlResume: null,
    cancelInfo: null,
    humanInputRequest: null,
    retroRecorded: [],
    artifactUpdates: [],
    memberCosts: new Map(),
    stances: new Map(),
    interrupted: false,
    connected: true,
    cancelRequested: false,
    cancelError: null,
    roundStartIndex: 0,
  };
}

/** Give the transcript body real scroll geometry (jsdom has none). */
function setGeometry(el: HTMLElement, geo: { scrollHeight: number; clientHeight: number; scrollTop: number }) {
  Object.defineProperty(el, "scrollHeight", { configurable: true, get: () => geo.scrollHeight });
  Object.defineProperty(el, "clientHeight", { configurable: true, get: () => geo.clientHeight });
  let top = geo.scrollTop;
  Object.defineProperty(el, "scrollTop", {
    configurable: true,
    get: () => top,
    set: (v: number) => {
      top = v;
    },
  });
}

describe("DiscussionTranscript — following new entries", () => {
  it("follows new entries only while the reader is at the bottom, and offers a way back otherwise", () => {
    const { rerender } = renderWithProviders(
      <DiscussionTranscript conversation={null} streamState={streamOf(2)} discussionStyle="ROUND_TABLE" />,
    );
    const log = screen.getByRole("log");
    // At the bottom: 1000 high, 400 visible, scrolled to 600.
    setGeometry(log, { scrollHeight: 1000, clientHeight: 400, scrollTop: 600 });
    fireEvent.scroll(log);
    rerender(<DiscussionTranscript conversation={null} streamState={streamOf(3)} discussionStyle="ROUND_TABLE" />);
    expect(log.scrollTop).toBe(1000);
    expect(screen.queryByTestId("transcript-new-messages")).not.toBeInTheDocument();

    // The reader scrolls up to re-read an earlier phase…
    setGeometry(log, { scrollHeight: 1000, clientHeight: 400, scrollTop: 100 });
    fireEvent.scroll(log);
    rerender(<DiscussionTranscript conversation={null} streamState={streamOf(4)} discussionStyle="ROUND_TABLE" />);
    // …and is not dragged away from it.
    expect(log.scrollTop).toBe(100);
    const jump = screen.getByTestId("transcript-new-messages");

    fireEvent.click(jump);
    expect(log.scrollTop).toBe(1000);
    expect(screen.queryByTestId("transcript-new-messages")).not.toBeInTheDocument();
  });

  it("is a polite log region, and the HTML toggle states whether it is on", () => {
    renderWithProviders(
      <DiscussionTranscript conversation={null} streamState={streamOf(1)} discussionStyle="ROUND_TABLE" />,
    );
    const log = screen.getByRole("log");
    expect(log).toHaveAttribute("aria-live", "polite");
    expect(log).toHaveAttribute("aria-relevant", "additions");

    const toggle = screen.getByRole("button", { name: /HTML/ });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-pressed", "true");
  });

  it("announces a pause and moves focus to the approval banner", () => {
    const conv = {
      id: "gc-1",
      groupId: "g",
      userId: "u",
      state: "AWAITING_APPROVAL",
      originalQuestion: "Q?",
      transcript: [entry(0)],
      memberConversationIds: {},
      currentPhaseIndex: 0,
      currentPhaseName: "Opinion",
      depth: 0,
      taskList: null,
      dynamicMembers: [],
      createdAgentIds: [],
      retainedAgentIds: [],
      created: "2026-06-09T12:00:00.000Z",
      lastModified: "2026-06-09T12:05:00.000Z",
    } as unknown as GroupConversation;
    renderWithProviders(<DiscussionTranscript conversation={conv} discussionStyle="ROUND_TABLE" />);

    expect(screen.getByTestId("transcript-pause-announcement")).toHaveTextContent(/waiting for your approval/i);
    expect(screen.getByTestId("approval-banner-anchor")).toHaveFocus();
  });
});
