import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TeamBuilder, type MemberSlot } from "../team-builder";
import { INITIAL_LLM_DEFAULTS } from "../member-validation";

/**
 * A board is normally one provider and one credential across every member, and
 * the workforce-wide `LlmDefaults` is what delivers that: a member's LLM fields
 * are blank by default and `effectiveLlm` falls back to the defaults, so the
 * vault key is picked once for the team.
 *
 * A new advisor must therefore start blank. Seeding it from the previous
 * advisor looks like the same convenience but is not: concrete per-member
 * copies stop tracking the defaults, so editing the workforce key afterwards
 * leaves the seeded advisors pointing at the old one.
 */
describe("TeamBuilder — advisor slots", () => {
  function member(overrides: Partial<MemberSlot> = {}): MemberSlot {
    return {
      id: crypto.randomUUID(),
      displayName: "",
      role: "",
      mode: "new",
      agentId: "",
      createdAgentId: "",
      systemPrompt: "",
      provider: "",
      model: "",
      apiKey: "",
      ...overrides,
    };
  }

  function render(members: MemberSlot[], onMembersChange: (m: MemberSlot[]) => void) {
    renderWithProviders(
      <TeamBuilder
        boardName="Board"
        onBoardNameChange={vi.fn()}
        boardDescription=""
        onBoardDescriptionChange={vi.fn()}
        members={members}
        onMembersChange={onMembersChange}
        llmDefaults={INITIAL_LLM_DEFAULTS}
        onLlmDefaultsChange={vi.fn()}
      />
    );
  }

  it("adds an advisor whose LLM fields are blank, so it inherits the workforce defaults", async () => {
    const onMembersChange = vi.fn();
    const existing = member({
      displayName: "First",
      provider: "anthropic",
      model: "claude-sonnet-5",
      apiKey: "${vault:openai-prod}",
    });
    render([existing], onMembersChange);

    await userEvent.setup().click(screen.getByTestId("add-advisor-btn"));

    const added = onMembersChange.mock.calls[0]![0]!.at(-1)!;
    expect(added).toMatchObject({
      // Blank, NOT copied from the advisor above — see the suite comment.
      provider: "",
      model: "",
      apiKey: "",
      displayName: "",
      role: "",
      systemPrompt: "",
      mode: "new",
    });
  });

  /**
   * `createdAgentId` tracks the id a partially failed creation minted, so a
   * retry reuses it. A fresh slot has never been created, so it must start
   * empty or the wizard would adopt a stale agent.
   */
  it("gives a new advisor an empty createdAgentId", async () => {
    const onMembersChange = vi.fn();
    render([member({ createdAgentId: "already-made-agent-1" })], onMembersChange);

    await userEvent.setup().click(screen.getByTestId("add-advisor-btn"));

    expect(onMembersChange.mock.calls[0]![0]!.at(-1)!.createdAgentId).toBe("");
  });

  it("appends rather than replacing, and gives each advisor its own id", async () => {
    const onMembersChange = vi.fn();
    const first = member({ displayName: "First" });
    render([first], onMembersChange);

    await userEvent.setup().click(screen.getByTestId("add-advisor-btn"));

    const next = onMembersChange.mock.calls[0]![0]!;
    expect(next).toHaveLength(2);
    expect(next[0]).toBe(first);
    expect(next[1]!.id).not.toBe(first.id);
  });
});
