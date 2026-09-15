/* ──────────────────────────────────────────────
   Rebuilding a transcript from the REAL snapshot wire shape
   ────────────────────────────────────────────── */

import { describe, it, expect } from "vitest";
import { stepsToMessages } from "./snapshot";

/**
 * Fixtures below use the shape the backend actually sends —
 * SimpleConversationMemorySnapshot.SimpleConversationStep, which is
 * `{ conversationStep: [{key, value, ...}], timestamp }`.
 *
 * The old fixtures invented `{ input, output }`, a shape the backend cannot
 * produce (ConversationMemoryUtilities only ever pushes ConversationStepData
 * with keys "input:initial", "output*", "quickReplies*", "actions*"). Tests
 * built on the invention passed while the product wiped the transcript.
 */
const step = (data: Array<{ key: string; value: unknown }>) => ({
  conversationStep: data,
  timestamp: "2026-07-21T10:00:00Z",
});

describe("stepsToMessages", () => {
  it("maps input:initial to a user message", () => {
    const msgs = stepsToMessages([step([{ key: "input:initial", value: "hello" }])]);

    expect(msgs).toHaveLength(1);
    expect(msgs[0]).toMatchObject({ role: "user", content: "hello" });
  });

  it("maps an output entry to an agent message", () => {
    const msgs = stepsToMessages([
      step([{ key: "output:text:MyPackage:1", value: ["Hi there"] }]),
    ]);

    expect(msgs).toHaveLength(1);
    expect(msgs[0]).toMatchObject({ role: "agent", content: "Hi there" });
  });

  it("keeps user input before the agent reply within a step", () => {
    const msgs = stepsToMessages([
      step([
        { key: "input:initial", value: "what is 2+2" },
        { key: "output:text:P:1", value: ["4"] },
      ]),
    ]);

    expect(msgs.map((m) => [m.role, m.content])).toEqual([
      ["user", "what is 2+2"],
      ["agent", "4"],
    ]);
  });

  it("handles an output value that is a bare string rather than a list", () => {
    // HITL writes its placeholder as a raw String.
    const msgs = stepsToMessages([
      step([{ key: "output", value: "Waiting for approval." }]),
    ]);

    expect(msgs[0]).toMatchObject({ role: "agent", content: "Waiting for approval." });
  });

  it("reads object-shaped output items", () => {
    const msgs = stepsToMessages([
      step([{ key: "output:text:P:1", value: [{ type: "text", text: "Hello!" }] }]),
    ]);

    expect(msgs[0]).toMatchObject({ role: "agent", content: "Hello!" });
  });

  it("ignores actions and quickReplies — they are not transcript content", () => {
    const msgs = stepsToMessages([
      step([
        { key: "actions", value: ["CONVERSATION_END"] },
        { key: "quickReplies:P:1", value: [{ value: "Yes" }] },
        { key: "input:initial", value: "bye" },
      ]),
    ]);

    expect(msgs.map((m) => m.content)).toEqual(["bye"]);
  });

  it("preserves step order across multiple steps", () => {
    const msgs = stepsToMessages([
      step([{ key: "input:initial", value: "first" }]),
      step([{ key: "input:initial", value: "second" }]),
    ]);

    expect(msgs.map((m) => m.content)).toEqual(["first", "second"]);
  });

  it("returns an empty list for the invented {input, output} shape", () => {
    // Guards against reintroducing the fictional shape: it must yield nothing,
    // so callers can detect the mismatch instead of silently rendering blanks.
    const msgs = stepsToMessages([
      { input: "hi", output: "there" } as never,
    ]);

    expect(msgs).toEqual([]);
  });

  it("tolerates a missing or empty conversationStep array", () => {
    expect(stepsToMessages([{ timestamp: "t" }])).toEqual([]);
    expect(stepsToMessages(undefined)).toEqual([]);
    expect(stepsToMessages([])).toEqual([]);
  });

  it("skips blank output text", () => {
    const msgs = stepsToMessages([step([{ key: "output", value: ["", "   "] }])]);

    expect(msgs).toEqual([]);
  });
});

describe("stepsToMessages — secret turns must never be re-rendered in clear", () => {
  it("masks a user input the caller knows was sent as a secret", () => {
    // The backend stores input:initial as PLAINTEXT unconditionally
    // (Conversation.java:337); only conversationOutput["input"] is masked, and
    // that key is filtered off the wire. So the client must mask it itself.
    const msgs = stepsToMessages(
      [step([{ key: "input:initial", value: "hunter2" }])],
      new Set(["hunter2"]),
    );

    expect(msgs[0].content).toBe("●●●●●●●●");
    expect(msgs.some((m) => m.content.includes("hunter2"))).toBe(false);
  });

  it("leaves ordinary input untouched", () => {
    const msgs = stepsToMessages(
      [step([{ key: "input:initial", value: "what is 2+2" }])],
      new Set(["hunter2"]),
    );

    expect(msgs[0].content).toBe("what is 2+2");
  });
});
