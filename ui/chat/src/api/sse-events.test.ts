/* ──────────────────────────────────────────────
   Interpreting SSE event payloads
   ────────────────────────────────────────────── */

import { describe, it, expect } from "vitest";
import {
  parseErrorMessage,
  parseDoneSnapshot,
  isSkippedTurn,
  isPausedState,
  extractOutputTexts,
  isTurnPaused,
} from "./sse-events";

describe("parseErrorMessage", () => {
  it("extracts the message from the backend's JSON error payload", () => {
    // RestAgentEngineStreaming emits {"message":"..."}, not a bare string.
    // Rendering the raw payload showed users a JSON blob.
    expect(parseErrorMessage('{"message":"Agent not deployed"}')).toBe(
      "Agent not deployed",
    );
  });

  it("falls back to the raw payload when it is not JSON", () => {
    expect(parseErrorMessage("something broke")).toBe("something broke");
  });

  it("falls back to the raw payload when JSON carries no message field", () => {
    expect(parseErrorMessage('{"code":500}')).toBe('{"code":500}');
  });

  it("returns a generic message for an empty payload", () => {
    expect(parseErrorMessage("")).toBe("The agent reported an error.");
  });
});

describe("parseDoneSnapshot", () => {
  it("parses the trimmed snapshot the done event carries", () => {
    const snapshot = parseDoneSnapshot(
      '{"conversationState":"READY","conversationOutputs":[{"quickReplies":[]}]}',
    );

    expect(snapshot?.conversationState).toBe("READY");
    expect(snapshot?.conversationOutputs).toHaveLength(1);
  });

  it("returns null for an empty payload", () => {
    expect(parseDoneSnapshot("")).toBeNull();
  });

  it("returns null for a malformed payload", () => {
    expect(parseDoneSnapshot("{not json")).toBeNull();
  });
});

describe("isSkippedTurn — a paused turn is not a skipped turn", () => {
  it("is NOT skipped when THIS turn caused the pause", () => {
    // The conversation was READY when we sent, so the turn was accepted and
    // paused. Telling the user "your message was not sent" would be a lie.
    expect(isSkippedTurn({ conversationState: "AWAITING_HUMAN" }, 0, "READY")).toBe(false);
  });

  it("IS skipped when the conversation was ALREADY paused before we sent", () => {
    expect(
      isSkippedTurn({ conversationState: "AWAITING_HUMAN" }, 0, "AWAITING_HUMAN"),
    ).toBe(true);
  });

  it("IS skipped when the agent was already busy with the previous turn", () => {
    expect(isSkippedTurn({ conversationState: "IN_PROGRESS" }, 0, "IN_PROGRESS")).toBe(true);
  });

  it("is NOT skipped when a fresh turn legitimately ends the conversation", () => {
    // ENDED reached BY this turn (CONVERSATION_END action) is a real outcome.
    expect(isSkippedTurn({ conversationState: "ENDED" }, 0, "READY")).toBe(false);
  });

  it("treats an unknown prior state as accepted rather than skipped", () => {
    expect(isSkippedTurn({ conversationState: "AWAITING_HUMAN" }, 0, null)).toBe(false);
  });
});

describe("isTurnPaused", () => {
  it("detects that this turn paused for approval", () => {
    expect(isTurnPaused({ conversationState: "AWAITING_HUMAN" }, "READY")).toBe(true);
  });

  it("is false when the conversation was already paused (that is a skip)", () => {
    expect(isTurnPaused({ conversationState: "AWAITING_HUMAN" }, "AWAITING_HUMAN")).toBe(false);
  });

  it("is false for a normal completion", () => {
    expect(isTurnPaused({ conversationState: "READY" }, "READY")).toBe(false);
  });
});

describe("isSkippedTurn", () => {
  // A turn dropped server-side (ConversationService.notifySkipped) arrives as
  // an ordinary `done` whose payload carries the PREVIOUS step's outputs.
  // Signal: zero tokens streamed AND a state the service skips on.
  it.each(["AWAITING_HUMAN", "IN_PROGRESS", "ENDED"] as const)(
    "detects a skipped turn when no tokens arrived and the conversation was already %s",
    (state) => {
      expect(isSkippedTurn({ conversationState: state }, 0, state)).toBe(true);
    },
  );

  it("is not a skipped turn when tokens were streamed", () => {
    expect(
      isSkippedTurn({ conversationState: "AWAITING_HUMAN" }, 1, "AWAITING_HUMAN"),
    ).toBe(false);
  });

  it("is not a skipped turn when the conversation ended READY", () => {
    expect(isSkippedTurn({ conversationState: "READY" }, 0, "READY")).toBe(false);
  });

  it("is not a skipped turn when the conversation errored", () => {
    expect(isSkippedTurn({ conversationState: "ERROR" }, 0, "READY")).toBe(false);
  });

  it("is not a skipped turn when there is no snapshot", () => {
    expect(isSkippedTurn(null, 0, "READY")).toBe(false);
  });
});

describe("isPausedState", () => {
  it("treats AWAITING_HUMAN as paused", () => {
    expect(isPausedState("AWAITING_HUMAN")).toBe(true);
  });

  it("does not treat other states as paused", () => {
    expect(isPausedState("READY")).toBe(false);
    expect(isPausedState("IN_PROGRESS")).toBe(false);
    expect(isPausedState(null)).toBe(false);
  });
});

describe("extractOutputTexts", () => {
  it("reads text from object-shaped output items", () => {
    expect(extractOutputTexts([{ type: "text", text: "hello" }])).toEqual([
      "hello",
    ]);
  });

  it("reads bare string output items", () => {
    // HITL writes the pending-approval placeholder and the reviewer-rejection
    // message as raw Strings into conversationOutputs[].output[]. Code that
    // only reads `.text` drops them silently — the user sees nothing at all.
    expect(
      extractOutputTexts(["Waiting for approval of send_email."]),
    ).toEqual(["Waiting for approval of send_email."]);
  });

  it("handles a mix of both shapes in order", () => {
    expect(
      extractOutputTexts([{ type: "text", text: "a" }, "b", { text: "c" }]),
    ).toEqual(["a", "b", "c"]);
  });

  it("skips inputField items — they drive the input, not the transcript", () => {
    expect(
      extractOutputTexts([
        { type: "inputField", subType: "password" },
        { type: "text", text: "after" },
      ]),
    ).toEqual(["after"]);
  });

  it("skips empty and blank entries", () => {
    expect(extractOutputTexts(["", "   ", { text: "" }, null, undefined])).toEqual([]);
  });

  it("returns an empty list for a missing output array", () => {
    expect(extractOutputTexts(undefined)).toEqual([]);
  });
});
