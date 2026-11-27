import { describe, it, expect } from "vitest";
import {
  INITIAL_LLM_DEFAULTS,
  memberIssue,
  type MemberSlot,
} from "@eddi/app-kit/components/workforce/wizard/member-validation";

const newAdvisor = {
  id: "m1",
  displayName: "Finance",
  role: "Finance",
  mode: "new",
  agentId: "",
  createdAgentId: "",
  systemPrompt: "You are the finance voice.",
  provider: "ollama",
  model: "",
  apiKey: "",
} as MemberSlot;

describe("memberIssue — creating a new advisor is admin-only", () => {
  it("is complete for an administrator", () => {
    expect(memberIssue(newAdvisor, INITIAL_LLM_DEFAULTS, true)).toBeNull();
  });

  it("flags a new advisor for a role that cannot call the setup endpoint", () => {
    expect(memberIssue(newAdvisor, INITIAL_LLM_DEFAULTS, false)).toBe("admin");
  });

  it("leaves an existing agent alone for that role", () => {
    const existing = { ...newAdvisor, mode: "existing", agentId: "a1" } as MemberSlot;
    expect(memberIssue(existing, INITIAL_LLM_DEFAULTS, false)).toBeNull();
  });

  it("does not flag an advisor an earlier attempt already provisioned", () => {
    const created = { ...newAdvisor, createdAgentId: "a2" } as MemberSlot;
    expect(memberIssue(created, INITIAL_LLM_DEFAULTS, false)).toBeNull();
  });
});
