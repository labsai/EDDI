import { describe, it, expect } from "vitest";
import { agentReplyCount, lastStepIndex, ratableSteps, stepIndexes } from "./rating-steps";
import { stepsToMessages } from "@/api/snapshot";
import type { ChatMessage } from "@/types";

let n = 0;
const msg = (role: "user" | "agent", content: string, extra: Partial<ChatMessage> = {}): ChatMessage => ({
  id: `m${n++}`,
  role,
  content,
  timestamp: 0,
  ...extra,
});

describe("stepIndexes", () => {
  it("puts the greeting on step 0 and opens a step with each user turn", () => {
    const messages = [msg("agent", "Hi"), msg("user", "q1"), msg("agent", "a1"), msg("user", "q2"), msg("agent", "a2")];
    expect(stepIndexes(messages)).toEqual([0, 1, 1, 2, 2]);
  });

  it("counts from the nearest message whose step is known, and gives notices none", () => {
    const messages = [
      msg("agent", "a5", { stepIndex: 5 }),
      msg("agent", "Undo failed", { kind: "notice" }),
      msg("user", "q"),
      msg("agent", "a"),
    ];
    expect(stepIndexes(messages)).toEqual([5, null, 6, 6]);
  });
});

describe("ratableSteps", () => {
  it("offers the thumbs once per step, under its last finished agent bubble", () => {
    const messages = [
      msg("agent", "Hi"),
      msg("user", "q"),
      msg("agent", "part 1"),
      msg("agent", "part 2"),
      msg("agent", "notice", { kind: "notice" }),
      msg("user", "q2"),
      msg("agent", "streaming", { isStreaming: true }),
    ];
    expect(ratableSteps(messages)).toEqual([0, null, null, 1, null, null, null]);
  });

  it("does not offer thumbs on an empty bubble", () => {
    expect(ratableSteps([msg("agent", "  ")])).toEqual([null]);
    expect(ratableSteps([msg("agent", "", { images: [{ uri: "x" }] })])).toEqual([0]);
  });

  it("counts the agent's replies without the greeting", () => {
    const messages = [msg("agent", "Hi"), msg("user", "q"), msg("agent", "a"), msg("user", "q2"), msg("agent", "b")];
    expect(agentReplyCount(messages)).toBe(2);
    expect(lastStepIndex(messages)).toBe(2);
    expect(lastStepIndex([])).toBeNull();
  });
});

describe("stepsToMessages", () => {
  const steps = [
    { conversationStep: [{ key: "output:text:a", value: ["Hello"] }] },
    { conversationStep: [{ key: "input:initial", value: "hi" }, { key: "output:text:b", value: ["Answer"] }] },
  ];

  it("stamps the server's step on a full history", () => {
    expect(stepsToMessages(steps, new Set(), null, true).map((m) => m.stepIndex)).toEqual([0, 1, 1]);
  });

  it("stamps nothing on a current-step-only response", () => {
    expect(stepsToMessages(steps).every((m) => m.stepIndex === undefined)).toBe(true);
  });
});
