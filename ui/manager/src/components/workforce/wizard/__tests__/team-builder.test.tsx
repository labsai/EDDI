import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TeamBuilder, type MemberSlot } from "../team-builder";

/**
 * A team is normally one provider and one credential across every member. The
 * wizard has a `SecretKeyPicker` per member, so without seeding, building a
 * five-advisor board meant picking the same vault key five times — and each
 * pasted plaintext copy would have been vaulted separately by the backend.
 */
describe("TeamBuilder — advisor slots", () => {
  function member(overrides: Partial<MemberSlot> = {}): MemberSlot {
    return {
      id: crypto.randomUUID(),
      displayName: "",
      role: "",
      mode: "new",
      agentId: "",
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
      />
    );
  }

  it("seeds a new advisor's LLM choice from the previous one", async () => {
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
      provider: "anthropic",
      model: "claude-sonnet-5",
      apiKey: "${vault:openai-prod}",
      // Identity is per-advisor and must NOT be inherited.
      displayName: "",
      role: "",
      systemPrompt: "",
    });
  });

  /** An "existing" slot points at a deployed agent and holds no credential. */
  it("ignores existing-agent slots when seeding", async () => {
    const onMembersChange = vi.fn();
    render(
      [
        member({ provider: "openai", model: "gpt-5.4", apiKey: "${vault:shared}" }),
        member({ mode: "existing", agentId: "agent-1" }),
      ],
      onMembersChange
    );

    await userEvent.setup().click(screen.getByTestId("add-advisor-btn"));

    expect(onMembersChange.mock.calls[0]![0]!.at(-1)!).toMatchObject({
      provider: "openai",
      model: "gpt-5.4",
      apiKey: "${vault:shared}",
    });
  });

  it("leaves the LLM fields empty when there is nothing to seed from", async () => {
    const onMembersChange = vi.fn();
    render([member({ mode: "existing", agentId: "agent-1" })], onMembersChange);

    await userEvent.setup().click(screen.getByTestId("add-advisor-btn"));

    expect(onMembersChange.mock.calls[0]![0]!.at(-1)!).toMatchObject({
      provider: "",
      model: "",
      apiKey: "",
    });
  });
});
