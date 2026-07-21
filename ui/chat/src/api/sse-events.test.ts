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

describe("isSkippedTurn", () => {
  // A turn dropped server-side (ConversationService.notifySkipped) arrives as
  // an ordinary `done` whose payload carries the PREVIOUS step's outputs.
  // Signal: zero tokens streamed AND a state the service skips on.
  it.each(["AWAITING_HUMAN", "IN_PROGRESS", "ENDED"] as const)(
    "detects a skipped turn when no tokens arrived and state is %s",
    (state) => {
      expect(isSkippedTurn({ conversationState: state }, 0)).toBe(true);
    },
  );

  it("is not a skipped turn when tokens were streamed", () => {
    expect(isSkippedTurn({ conversationState: "AWAITING_HUMAN" }, 1)).toBe(
      false,
    );
  });

  it("is not a skipped turn when the conversation ended READY", () => {
    expect(isSkippedTurn({ conversationState: "READY" }, 0)).toBe(false);
  });

  it("is not a skipped turn when the conversation errored", () => {
    expect(isSkippedTurn({ conversationState: "ERROR" }, 0)).toBe(false);
  });

  it("is not a skipped turn when there is no snapshot", () => {
    expect(isSkippedTurn(null, 0)).toBe(false);
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
